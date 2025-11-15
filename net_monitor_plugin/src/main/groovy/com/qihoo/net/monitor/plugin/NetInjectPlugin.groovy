package com.qihoo.net.monitor.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class NetInjectPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (project.plugins.hasPlugin('com.android.application')) {
            AppExtension android = project.extensions.getByType(AppExtension)
            android.registerTransform(new NetTransform(project))
            project.logger.lifecycle("NetInjectPlugin 已生效")
        }
    }
}