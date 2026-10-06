package com.hazbu.xcam.data
object Constants {
    const val PREFS_NAME = "xcam_prefs"
    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_IS_ENABLED = "is_enabled"
    const val KEY_IS_MIRRORED = "is_mirrored"
    const val KEY_ROTATION_ANGLE = "rotation_angle"
    const val AUTHORITY = "com.hazbu.xcam.provider"

    const val DEFAULT_CAPTURE_WIDTH = 1280
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val DUMMY_SURFACE_TEXTURE_ID = 999
    const val MIN_SESSION_DEBOUNCE_MS = 100L
    const val STREAM_FRAME_INTERVAL_MS = 33L
    const val KEY_ENABLE_CAPTURE_NOTIF = "enable_capture_notif"
    const val KEY_LATEST_PIPELINE_APP = "latest_pipeline_app"
    const val KEY_LATEST_PIPELINE_ROUTE = "latest_pipeline_route"
    const val KEY_LATEST_PIPELINE_TIME = "latest_pipeline_time"

    const val METHOD_RECORD_NODE = "record_node"
    const val METHOD_NOTIFY_CAPTURE = "notify_capture"
    const val CAPTURE_NOTIFICATION_CHANNEL_ID = "xcam_capture_channel"
    const val CAPTURE_NOTIFICATION_ID = 1001
}
