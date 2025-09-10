package com.qihoo.net.monitor.plugin

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.qihoo.net.monitor.plugin.asm.OkHttpClassVisitor
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

class OkHttpTransform extends Transform {
    private final Project project

    OkHttpTransform(Project project) {
        this.project = project
    }

    // 任务名称
    @Override
    String getName() {
        return "OkHttpInjectTransform"
    }

    // 输入类型：处理CLASS文件
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    // 关键修改1：AGP 3.3.3 中 Scope 需用非 QualifiedContent 包装的类型
    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    // 是否支持增量编译（简化版暂不支持）
    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation invocation) throws TransformException, InterruptedException, IOException {
        def outputProvider = invocation.outputProvider
        println("start - 1")
        // 关键修改3：AGP 3.3.3 中 outputProvider 可能为 null，增加空判断
        if (outputProvider == null) {
            project.logger.warn("OkHttpTransform: 输出提供者为空，跳过处理")
            return
        }
        // 遍历所有输入文件
        invocation.inputs.each { TransformInput input ->
            // 处理目录中的class文件
            input.directoryInputs.each { DirectoryInput dirInput ->
                File inputDir = dirInput.file
                if (inputDir.exists()) {
                    // 递归扫描并修改class文件
                    inputDir.eachFileRecurse { File file ->
                        if (file.name.endsWith(".class")) {
                            modifyClass(file)
                        }
                    }
                }

                // 输出修改后的文件到目标目录
                File outputDir = outputProvider.getContentLocation(
                        dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY
                )
                FileUtils.copyDirectory(inputDir, outputDir)
            }

            // 处理jar包（跳过第三方jar，只处理项目代码）
            input.jarInputs.each { JarInput jarInput ->
                File outputJar = outputProvider.getContentLocation(
                        jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR
                )
                FileUtils.copyFile(jarInput.file, outputJar)
            }
        }
    }

    // 修改单个class文件
    private void modifyClass(File file) {
        try {
            println("file name : ${file.name}")
            ClassReader cr = new ClassReader(FileUtils.readFileToByteArray(file))
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS)
            // 使用自定义ClassVisitor处理字节码
            OkHttpClassVisitor cv = new OkHttpClassVisitor(cw, project)
            cr.accept(cv, ClassReader.EXPAND_FRAMES)
            // 写回修改后的字节码
            FileUtils.writeByteArrayToFile(file, cw.toByteArray())
        } catch (Exception e) {
            project.logger.error("❌ 修改class失败：${file.absolutePath}", e)
        }
    }
}