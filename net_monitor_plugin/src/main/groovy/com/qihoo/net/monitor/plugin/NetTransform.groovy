package com.qihoo.net.monitor.plugin

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class NetTransform extends Transform {
    private final Project project

    NetTransform(Project project) {
        this.project = project
    }

    @Override
    String getName() {
        return "NetInjectTransform"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    // 关键：确保作用域包含Lib工程（本地Lib和子模块）
    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return new HashSet<QualifiedContent.Scope>() {
            {
                add(QualifiedContent.Scope.PROJECT)
                add(QualifiedContent.Scope.SUB_PROJECTS)
                add(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
            }
        }
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation invocation) throws TransformException, InterruptedException, IOException {
        LogUtils.init(project)
        def outputProvider = invocation.outputProvider
        project.logger.lifecycle("开始执行 NetTransform（模块：${project.name}）")

        // 1. 空判断与清理输出（避免旧文件残留）
        if (outputProvider == null) {
            project.logger.warn("NetTransform: 输出提供者为空，跳过处理")
            return
        }
        outputProvider.deleteAll() // 每次构建前清空输出目录

        // 2. 遍历所有输入（目录 + Jar）
        invocation.inputs.each { TransformInput input ->
            // 处理目录输入（源码编译后的class目录，如App/Lib的build/intermediates/classes）
            handleDirectoryInput(input.directoryInputs, outputProvider)
            // 处理Jar输入（打包后的Jar/AAR，如Lib的classes.jar、第三方依赖Jar）
            handleJarInput(input.jarInputs, outputProvider)
        }

        project.logger.lifecycle("NetTransform 执行完成（模块：${project.name}）")
    }

    // ------------------------------ 目录输入处理 ------------------------------
    /**
     * 处理目录形式的class（遍历文件 → 修改 → 复制到输出目录）
     */
    private void handleDirectoryInput(Collection<DirectoryInput> directoryInputs, TransformOutputProvider outputProvider) {
        directoryInputs.each { DirectoryInput dirInput ->
            File inputDir = dirInput.file
            if (!inputDir.exists()) return

            // 1. 遍历目录下所有class文件，逐个修改
            inputDir.eachFileRecurse { File classFile ->
                if (classFile.name.endsWith(".class")) {
                    project.logger.debug("正在修改目录中的class：${classFile.absolutePath}")
                    // 通用字节码修改逻辑
                    byte[] modifiedBytes = modifyClassBytes(FileUtils.readFileToByteArray(classFile))
                    // 写回修改后的字节到原文件（后续会复制到输出目录）
                    FileUtils.writeByteArrayToFile(classFile, modifiedBytes)
                }
            }

            // 2. 将修改后的目录复制到输出目录（保持原目录结构）
            File outputDir = outputProvider.getContentLocation(
                    dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY
            )
            FileUtils.copyDirectory(inputDir, outputDir)
            project.logger.debug("目录输出完成：${outputDir.absolutePath}")
        }
    }

    /**
     * 处理Jar形式的class（解压Jar → 修改class → 重新打包 → 输出到新Jar）
     */
    private void handleJarInput(Collection<JarInput> jarInputs, TransformOutputProvider outputProvider) {
        jarInputs.each { JarInput jarInput ->
            File inputJar = jarInput.file
            if (!inputJar.exists()) {
                project.logger.warn("Jar文件不存在: ${inputJar.absolutePath}")
                return
            }

            String jarName = jarInput.name
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - 4)
            }
            File outputJar = outputProvider.getContentLocation(
                    "${jarName}_modified",
                    jarInput.contentTypes,
                    jarInput.scopes,
                    Format.JAR
            )

            // 确保输出目录存在
            if (!outputJar.parentFile.exists()) {
                outputJar.parentFile.mkdirs()
            }

            JarFile inputJarFile = new JarFile(inputJar)
            JarOutputStream outputJarStream = new JarOutputStream(new FileOutputStream(outputJar))

            try {
                Enumeration<JarEntry> entries = inputJarFile.entries()
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement()
                    String entryName = entry.name

                    // 处理目录或不需要修改的文件
                    if (entry.isDirectory() || !entryName.endsWith(".class")) {
                        outputJarStream.putNextEntry(new JarEntry(entryName))
                        // 手动复制输入流到输出流
                        InputStream inputStream = inputJarFile.getInputStream(entry)
                        try {
                            byte[] buffer = new byte[4096]
                            int bytesRead
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputJarStream.write(buffer, 0, bytesRead)
                            }
                        } finally {
                            inputStream.close()
                        }
                        outputJarStream.closeEntry()
                        continue
                    }

                    // 处理需要修改的class文件
                    project.logger.debug("正在修改Jar中的class：${entryName}（来自：${inputJar.name}）")
                    InputStream inputStream = inputJarFile.getInputStream(entry)
                    byte[] originalBytes = null
                    try {
                        // 关键修改：手动读取输入流内容到字节数组，不依赖FileUtils
                        originalBytes = readInputStreamToByteArray(inputStream)
                    } finally {
                        inputStream.close()
                    }

                    byte[] modifiedBytes = modifyClassBytes(originalBytes)

                    outputJarStream.putNextEntry(new JarEntry(entryName))
                    outputJarStream.write(modifiedBytes)
                    outputJarStream.closeEntry()
                }
                project.logger.debug("Jar输出完成：${outputJar.absolutePath}")
            } catch (Exception e) {
                project.logger.error("处理Jar文件失败: ${inputJar.absolutePath}", e)
                throw e
            } finally {
                outputJarStream.close()
                inputJarFile.close()
            }
        }
    }

/**
 * 手动实现输入流到字节数组的转换，完全不依赖commons-io
 */
    private static byte[] readInputStreamToByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        byte[] buffer = new byte[4096]
        int bytesRead
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        return outputStream.toByteArray()
    }

    /**
     * 通用字节码修改逻辑（接收原始字节 → 用ASM处理 → 返回修改后字节）
     * 无论class来自目录还是Jar，都调用此方法，实现逻辑复用
     */
    private byte[] modifyClassBytes(byte[] originalBytes) {
        try {
            ClassReader cr = new ClassReader(originalBytes)
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS)
            NetClassVisitor cv = new NetClassVisitor(cw, project)
            cr.accept(cv, ClassReader.EXPAND_FRAMES)
            return cw.toByteArray()
        } catch (Exception e) {
            project.logger.error(">>>>>>>>>>>>>>>>>>>>>>>>>>>字节码修改失败<<<<<<<<<<<<<<<<<<<<<<<<<<<", e)
            return originalBytes // 失败时返回原始字节，避免构建崩溃
        }
    }
}