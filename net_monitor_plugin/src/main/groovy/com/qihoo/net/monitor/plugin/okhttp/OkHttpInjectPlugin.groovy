package com.qihoo.net.monitor.plugin.okhttp

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class OkHttpInjectPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (project.plugins.hasPlugin('com.android.application')) {
            AppExtension android = project.extensions.getByType(AppExtension)
            android.registerTransform(new OkHttpTransform(project))
            project.logger.lifecycle("OkHttpInjectPlugin 已生效")
        }
    }
}