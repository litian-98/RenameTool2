package com.github.litian98.renametool2.toolWindow.constant

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project

object Notifications {

    fun create(project: Project, title: String, content: String) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("TraverseAndNotifyAction")
        val notification = notificationGroup.createNotification(title, content, NotificationType.INFORMATION)
        Notifications.Bus.notify(notification, project)
    }
}