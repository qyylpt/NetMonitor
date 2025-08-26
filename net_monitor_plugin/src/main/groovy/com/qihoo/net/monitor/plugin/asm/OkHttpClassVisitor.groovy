package com.qihoo.net.monitor.plugin.asm

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.gradle.api.Project

class OkHttpClassVisitor extends ClassVisitor {
    private final Project project

    OkHttpClassVisitor(ClassVisitor cv, Project project) {
        super(Opcodes.ASM9, cv)
        this.project = project
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions)
        return new OkHttpBuilderVisitor(Opcodes.ASM9, mv, access, name, desc)
    }
}