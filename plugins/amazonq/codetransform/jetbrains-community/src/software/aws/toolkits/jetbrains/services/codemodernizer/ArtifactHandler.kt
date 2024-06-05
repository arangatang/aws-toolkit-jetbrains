// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDefaultExecutor
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDifferentiatedDialog
import com.intellij.openapi.vcs.changes.patch.ApplyPatchMode
import com.intellij.openapi.vcs.changes.patch.ImportToShelfExecutor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.ssooidc.model.SsoOidcException
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.exists
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.core.coroutines.getCoroutineBgContext
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.NoTokenInitializedException
import software.aws.toolkits.jetbrains.services.amazonq.CODE_TRANSFORM_TROUBLESHOOT_DOC_ARTIFACT
import software.aws.toolkits.jetbrains.services.codemodernizer.client.GumbyClient
import software.aws.toolkits.jetbrains.services.codemodernizer.commands.CodeTransformMessageListener
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerArtifact
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeTransformHilDownloadArtifact
import software.aws.toolkits.jetbrains.services.codemodernizer.model.DownloadArtifactResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.DownloadFailureReason
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getPathToHilArtifactDir
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.isCodeTransformAvailable
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.openTroubleshootingGuideNotificationAction
import software.aws.toolkits.jetbrains.utils.notifyStickyInfo
import software.aws.toolkits.jetbrains.utils.notifyStickyWarn
import software.aws.toolkits.resources.AwsToolkitBundle.message
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

const val DOWNLOAD_PROXY_WILDCARD_ERROR: String = "Dangling meta character '*' near index 0"
const val DOWNLOAD_SSL_HANDSHAKE_ERROR: String = "Unable to execute HTTP request: javax.net.ssl.SSLHandshakeException"

class ArtifactHandler(private val project: Project, private val clientAdaptor: GumbyClient) {
    private val telemetry = CodeTransformTelemetryManager.getInstance(project)
    private val downloadedArtifacts = mutableMapOf<JobId, Path>()
    private val downloadedSummaries = mutableMapOf<JobId, TransformationSummary>()

    private var isCurrentlyDownloading = AtomicBoolean(false)
    internal suspend fun displayDiff(job: JobId) {
        LOG.error("In displayDiff artifact")
        if (isCurrentlyDownloading.get()) return
        when (val result = downloadArtifact(job)) {
            is DownloadArtifactResult.Success -> displayDiffUsingPatch(result.artifact.patch, job)
            is DownloadArtifactResult.Failure -> notifyUnableToApplyPatch(result.errorMessage)
            is DownloadArtifactResult.KnownDownloadFailure -> notifyUnableToDownload(result.failureReason)
        }
    }

    private fun notifyDownloadStart() {
        notifyStickyInfo(
            message("codemodernizer.notification.info.download.started.title"),
            message("codemodernizer.notification.info.download.started.content"),
            project,
        )
    }

    private suspend fun unzipToPath(byteArrayList: List<ByteArray>, outputDirPath: Path? = null): Pair<Path, Int> {
        val zipFilePath = withContext(getCoroutineBgContext()) {
            if (outputDirPath == null) {
                Files.createTempFile(null, ".zip")
            } else {
                Files.createTempFile(outputDirPath, null, ".zip")
            }
        }
        var totalDownloadBytes = 0
        withContext(getCoroutineBgContext()) {
            Files.newOutputStream(zipFilePath).use {
                for (bytes in byteArrayList) {
                    it.write(bytes)
                    totalDownloadBytes += bytes.size
                }
            }
        }
        return zipFilePath to totalDownloadBytes
    }

    suspend fun downloadHilArtifact(jobId: JobId, artifactId: String, tmpDir: File): CodeTransformHilDownloadArtifact? {
        val downloadResultsResponse = clientAdaptor.downloadExportResultArchive(jobId, artifactId)

        return try {
            val tmpPath = tmpDir.toPath()
            val (downloadZipFilePath, _) = unzipToPath(downloadResultsResponse, tmpPath)
            LOG.info { "Successfully converted the hil artifact download to a zip at ${downloadZipFilePath.toAbsolutePath()}." }
            CodeTransformHilDownloadArtifact.create(downloadZipFilePath, getPathToHilArtifactDir(tmpPath))
        } catch (e: Exception) {
            // In case if unzip or file operations fail
            val errorMessage = "Unexpected error when saving downloaded hil artifact: ${e.localizedMessage}"
            telemetry.error(errorMessage)
            LOG.error { errorMessage }
            null
        }
    }

    suspend fun downloadArtifact(job: JobId): DownloadArtifactResult {
        LOG.error("In download artifact")
        isCurrentlyDownloading.set(true)

        val downloadStartTime = Instant.now()
        try {
            // 1. Attempt reusing previously downloaded artifact for job
            val previousArtifact = downloadedArtifacts.getOrDefault(job, null)
            if (previousArtifact != null && previousArtifact.exists()) {
                val zipPath = previousArtifact.toAbsolutePath().toString()
                return try {
                    val artifact = CodeModernizerArtifact.create(zipPath)
                    downloadedSummaries[job] = artifact.summary
                    DownloadArtifactResult.Success(artifact, zipPath)
                } catch (e: RuntimeException) {
                    LOG.error { e.message.toString() }
                    DownloadArtifactResult.Failure(e.message.orEmpty())
                }
            }

            // 2. Download the data
            notifyDownloadStart()

            LOG.info { "Verifying user is authenticated prior to download" }
            if (!isCodeTransformAvailable(project)) {
                CodeModernizerManager.getInstance(project).handleResumableDownloadArtifactFailure(job)
                return DownloadArtifactResult.KnownDownloadFailure(DownloadFailureReason.CREDENTIALS_EXPIRED)
            }

            LOG.info { "About to download the export result archive" }
            val downloadResultsResponse = clientAdaptor.downloadExportResultArchive(job)

            // 3. Convert to zip
            LOG.info { "Downloaded the export result archive, about to transform to zip" }

            val (path, totalDownloadBytes) = unzipToPath(downloadResultsResponse)
            val zipPath = path.toAbsolutePath().toString()
            LOG.info { "Successfully converted the download to a zip at $zipPath." }

            // 4. Deserialize zip to CodeModernizerArtifact
            var telemetryErrorMessage: String? = null
            return try {
                val output = DownloadArtifactResult.Success(CodeModernizerArtifact.create(zipPath), zipPath)
                downloadedArtifacts[job] = path
                output
            } catch (e: RuntimeException) {
                LOG.error { e.message.toString() }
                LOG.error { "Unable to find patch for file: $zipPath" }
                telemetryErrorMessage = "Unexpected error when downloading result ${e.localizedMessage}"
                DownloadArtifactResult.Failure(e.message.orEmpty())
            } finally {
                telemetry.jobArtifactDownloadAndDeserializeTime(
                    downloadStartTime,
                    job,
                    totalDownloadBytes,
                    telemetryErrorMessage,
                )
            }
        } catch (e: Exception) {
            LOG.error("In ArtifactHandler: ${e.localizedMessage}")
            return when {
                e is SsoOidcException || e is NoTokenInitializedException -> {
                    CodeModernizerManager.getInstance(project).handleResumableDownloadArtifactFailure(job)
                    DownloadArtifactResult.KnownDownloadFailure(DownloadFailureReason.CREDENTIALS_EXPIRED)
                }

                e.message.toString().contains(DOWNLOAD_PROXY_WILDCARD_ERROR) ->
                    DownloadArtifactResult.KnownDownloadFailure(DownloadFailureReason.PROXY_WILDCARD_ERROR)

                e.message.toString().contains(DOWNLOAD_SSL_HANDSHAKE_ERROR) ->
                    DownloadArtifactResult.KnownDownloadFailure(DownloadFailureReason.SSL_HANDSHAKE_ERROR)

                else -> DownloadArtifactResult.Failure(e.message.orEmpty())
            }
        } finally {
            isCurrentlyDownloading.set(false)
        }
    }

    /**
     * Opens the built-in patch dialog to display the diff and allowing users to apply the changes locally.
     */
    internal fun displayDiffUsingPatch(patchFile: VirtualFile, jobId: JobId) {
        runInEdt {
            val dialog = ApplyPatchDifferentiatedDialog(
                project,
                ApplyPatchDefaultExecutor(project),
                listOf(ImportToShelfExecutor(project)),
                ApplyPatchMode.APPLY,
                patchFile,
                null,
                ChangeListManager.getInstance(project)
                    .addChangeList(message("codemodernizer.patch.name"), ""),
                null,
                null,
                null,
                false,
            )
            dialog.isModal = true

            telemetry.vcsDiffViewerVisible(jobId) // download succeeded
            if (dialog.showAndGet()) {
                telemetry.vcsViewerSubmitted(jobId)
            } else {
                telemetry.vscViewerCancelled(jobId)
            }
        }
    }

    fun notifyUnableToDownload(error: DownloadFailureReason) {
        LOG.error { "Unable to download artifact: $error" }
        when (error) {
            DownloadFailureReason.PROXY_WILDCARD_ERROR -> notifyStickyWarn(
                message("codemodernizer.notification.warn.view_diff_failed.title"),
                message("codemodernizer.notification.warn.download_failed_wildcard.content", error),
                project,
            )

            DownloadFailureReason.SSL_HANDSHAKE_ERROR -> notifyStickyWarn(
                message("codemodernizer.notification.warn.view_diff_failed.title"),
                message("codemodernizer.notification.warn.download_failed_ssl.content", error),
                project,
            )

            DownloadFailureReason.CREDENTIALS_EXPIRED -> {
                CodeTransformMessageListener.instance.onCheckAuth()
                notifyStickyWarn(
                    message("codemodernizer.notification.warn.expired_credentials.title"),
                    message("codemodernizer.notification.warn.download_failed_expired_credentials.content"),
                    project,
                    listOf(NotificationAction.createSimpleExpiring(message("codemodernizer.notification.warn.action.reauthenticate")) {
                        CodeTransformMessageListener.instance.onReauthStarted()
                    })
                )
            }
        }


    }

    fun notifyUnableToApplyPatch(errorMessage: String) {
        notifyStickyWarn(
            message("codemodernizer.notification.warn.view_diff_failed.title"),
            message("codemodernizer.notification.warn.view_diff_failed.content", errorMessage),
            project,
            listOf(
                openTroubleshootingGuideNotificationAction(
                    CODE_TRANSFORM_TROUBLESHOOT_DOC_ARTIFACT
                )
            ),
        )
    }

    fun notifyUnableToShowSummary() {
        LOG.error { "Unable to display summary" }
        notifyStickyWarn(
            message("codemodernizer.notification.warn.view_summary_failed.title"),
            message("codemodernizer.notification.warn.view_summary_failed.content"),
            project,
            listOf(
                openTroubleshootingGuideNotificationAction(
                    CODE_TRANSFORM_TROUBLESHOOT_DOC_ARTIFACT
                )
            ),
        )
    }

    fun displayDiffAction(jobId: JobId) = runReadAction {
        telemetry.vcsViewerClicked(jobId)
        projectCoroutineScope(project).launch {
            displayDiff(jobId)
        }
    }

    fun getSummary(job: JobId) = downloadedSummaries[job]

    private fun showSummaryFromFile(summaryFile: File) {
        val summaryMarkdownVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(summaryFile)
        if (summaryMarkdownVirtualFile != null) {
            runInEdt {
                FileEditorManager.getInstance(project).openFile(summaryMarkdownVirtualFile, true)
            }
        }
    }


    fun showTransformationSummary(job: JobId) {
        LOG.error("In showTransformationSummary artifact")
        if (isCurrentlyDownloading.get()) return
        runReadAction {
            projectCoroutineScope(project).launch {
                when (val result = downloadArtifact(job)) {
                    is DownloadArtifactResult.Success -> showSummaryFromFile(result.artifact.summaryMarkdownFile)
                    is DownloadArtifactResult.Failure -> notifyUnableToShowSummary()
                    is DownloadArtifactResult.KnownDownloadFailure -> notifyUnableToDownload(result.failureReason)
                }
            }
        }
    }

    companion object {
        val LOG = getLogger<ArtifactHandler>()
    }
}
