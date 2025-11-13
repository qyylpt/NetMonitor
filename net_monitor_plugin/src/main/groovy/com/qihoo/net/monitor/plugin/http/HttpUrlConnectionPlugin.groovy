package com.qihoo.net.monitor.plugin.http

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class HttpUrlConnectionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (project.plugins.hasPlugin('com.android.application')) {
            AppExtension android = project.extensions.getByType(AppExtension)
            android.registerTransform(new HttpUrlConnectTransform(project))
            project.logger.lifecycle("HttpUrlConnectionPlugin 已生效")
        }
    }
}