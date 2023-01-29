package com.audiobookshelf.app.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import kotlin.concurrent.schedule

data class MediaProgressSyncData(
  var timeListened:Long, // seconds
  var duration:Double, // seconds
  var currentTime:Double // seconds
)

data class SyncResult(
  var serverSyncAttempted:Boolean,
  var serverSyncSuccess:Boolean?,
  var serverSyncMessage:String?
)

class MediaProgressSyncer(val playerNotificationService:PlayerNotificationService, private val apiHandler: ApiHandler) {
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning:Boolean = false

  private var lastSyncTime:Long = 0
  private var failedSyncs:Int = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle get() = currentPlaybackSession?.displayTitle ?: "Unset"
  private val currentIsLocal get() = currentPlaybackSession?.isLocal == true
  val currentSessionId get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration get() = currentPlaybackSession?.duration ?: 0.0

  fun start(playbackSession:PlaybackSession) {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    Log.d(tag, "start: init last sync time $lastSyncTime")
    currentPlaybackSession = playbackSession.clone()

    listeningTimerTask = Timer("ListeningTimer", false).schedule(15000L, 15000L) {
      Handler(Looper.getMainLooper()).post() {
        if (playerNotificationService.currentPlayer.isPlaying) {
          // Set auto sleep timer if enabled and within start/end time
          playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

          // Only sync with server on unmetered connection every 15s OR sync with server if last sync time is >= 60s
          val shouldSyncServer = PlayerNotificationService.isUnmeteredNetwork || System.currentTimeMillis() - lastSyncTime >= METERED_CONNECTION_SYNC_INTERVAL

          val currentTime = playerNotificationService.getCurrentTimeSeconds()
          if (currentTime > 0) {
            sync(shouldSyncServer, currentTime) { syncResult ->
              Log.d(tag, "Sync complete")

              currentPlaybackSession?.let { playbackSession ->
                MediaEventManager.saveEvent(playbackSession, syncResult)
              }
            }
          }
        }
      }
    }
  }

  fun play(playbackSession:PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
  }

  fun stop(shouldSync:Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      return cb()
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime = if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      sync(true, currentTime) { syncResult ->
        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        lastSyncTime = 0L
        Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      }
    } else {
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult ->
      reset()

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.finishedEvent(playbackSession, syncResult)
      }

      cb()
    }
  }

  fun seek() {
    currentPlaybackSession?.currentTime = playerNotificationService.getCurrentTimeSeconds()
    Log.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime

      MediaEventManager.syncEvent(mediaProgress, "Received from server get media progress request while playback session open")

      DeviceManager.dbManager.saveLocalPlaybackSession(it)
      saveLocalProgress(it)
    }
  }

  fun sync(shouldSyncServer:Boolean, currentTime:Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      return cb(null)
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    val syncData = MediaProgressSyncData(listeningTimeToAdd,currentPlaybackDuration,currentTime)

    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(tag, "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}")
      return cb(null)
    }

    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        DeviceManager.dbManager.saveLocalPlaybackSession(it)
        saveLocalProgress(it)
        lastSyncTime = System.currentTimeMillis()

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this server
        if (shouldSyncServer && !it.libraryItemId.isNullOrEmpty() && it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId) {
          apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
            Log.d(
              tag,
              "Local progress sync data sent to server $currentDisplayTitle for time $currentTime"
            )

            if (syncSuccess) {
              failedSyncs = 0
              playerNotificationService.alertSyncSuccess()
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                playerNotificationService.alertSyncFailing() // Show alert in client
                failedSyncs = 0
              }
              Log.e(tag, "Local Progress sync failed ($failedSyncs) to send to server $currentDisplayTitle for time $currentTime")
            }

            cb(SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          cb(SyncResult(false, null, null))
        }
      }
    } else if (shouldSyncServer) {
      Log.d(tag, "sync: currentSessionId=$currentSessionId")
      apiHandler.sendProgressSync(currentSessionId, syncData) { syncSuccess, errorMsg ->
        if (syncSuccess) {
          Log.d(tag, "Progress sync data sent to server $currentDisplayTitle for time $currentTime")
          failedSyncs = 0
          playerNotificationService.alertSyncSuccess()
          lastSyncTime = System.currentTimeMillis()
        } else {
          failedSyncs++
          if (failedSyncs == 2) {
            playerNotificationService.alertSyncFailing() // Show alert in client
            failedSyncs = 0
          }
          Log.e(tag, "Progress sync failed ($failedSyncs) to send to server $currentDisplayTitle for time $currentTime")
        }
        cb(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      cb(SyncResult(false, null, null))
    }
  }

  private fun saveLocalProgress(playbackSession:PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        Log.d(tag, "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%")
      }
    }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
  }
}
