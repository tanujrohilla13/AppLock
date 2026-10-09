package com.tanuj.applock

import android.app.admin.DeviceAdminReceiver

/**
 * Enabling this as a Device Admin blocks App Lock from being uninstalled until
 * the owner turns admin off (which itself happens behind the PIN via MainActivity).
 */
class AdminReceiver : DeviceAdminReceiver()
