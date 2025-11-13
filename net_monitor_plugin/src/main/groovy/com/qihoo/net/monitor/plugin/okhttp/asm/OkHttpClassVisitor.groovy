package com.qihoo.net.monitor.plugin.okhttp.asm

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.gradle.api.Project

class OkHttpClassVisitor extends ClassVisitor {
    private final Project project
    // 保存当前处理的类名
    private String currentClassName

    OkHttpClassVisitor(ClassVisitor cv, Project project) {
        super(Opcodes.ASM7, cv)
        this.project = project
    }

    // 类访问入口：记录当前类名
    @Override
    void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.currentClassName = name // 保存当前类名（格式如 com/example/TargetClass）
        super.visit(version, access, name, signature, superName, interfaces)
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions)
        return new OkHttpBuilderVisitor(Opcodes.ASM7, mv, access, name, desc, currentClassName)
    }
}