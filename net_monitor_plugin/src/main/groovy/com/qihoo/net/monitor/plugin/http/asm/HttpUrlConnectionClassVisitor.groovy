package com.qihoo.net.monitor.plugin.http.asm

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.gradle.api.Project

class HttpUrlConnectionClassVisitor extends ClassVisitor {
    private final Project project

    HttpUrlConnectionClassVisitor(ClassVisitor cv, Project project) {
        super(Opcodes.ASM7, cv)
        this.project = project
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions)
        return new HttpUrlConnectionMethodVisitor(Opcodes.ASM7, mv, access, name, desc)
    }
}