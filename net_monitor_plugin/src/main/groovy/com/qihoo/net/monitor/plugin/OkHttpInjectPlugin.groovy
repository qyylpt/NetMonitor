package com.qihoo.net.monitor.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class OkHttpInjectPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // 仅对Android应用模块生效
        if (project.plugins.hasPlugin('com.android.application')) {
            // 获取Android构建扩展配置
            AppExtension android = project.extensions.getByType(AppExtension)
            // 注册字节码转换任务
            android.registerTransform(new OkHttpTransform(project))
            project.logger.lifecycle("✅ OkHttpInjectPlugin 已生效")
        }
    }
}