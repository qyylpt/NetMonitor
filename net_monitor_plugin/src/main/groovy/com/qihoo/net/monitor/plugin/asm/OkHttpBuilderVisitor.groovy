package com.qihoo.net.monitor.plugin.asm

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

class OkHttpBuilderVisitor extends AdviceAdapter {
    // 标记是否刚调用过 Builder 的构造函数
    private boolean afterBuilderInit = false

    OkHttpBuilderVisitor(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc)
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        // 第一步：先处理构造函数调用后的插入逻辑
        if (afterBuilderInit) {
            // 插入 addInterceptor 和 eventListenerFactory
            insertInterceptorAndListener()
            afterBuilderInit = false  // 重置标记，避免重复插入
        }

        // 第二步：检测 Builder 的构造函数调用（标记插入点）
        if (opcode == Opcodes.INVOKESPECIAL
                && 'okhttp3/OkHttpClient$Builder'.equals(owner)
                && '<init>'.equals(name)
                && '()V'.equals(desc)) {
            afterBuilderInit = true  // 标记后续需要插入代码
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    /**
     * 在 Builder 构造函数后插入：
     * addInterceptor(new CustomInterceptor())
     * eventListenerFactory(NetworkEventListener.Factory(...))
     */
    private void insertInterceptorAndListener() {
        // ====== 1. 插入 addInterceptor(new CustomInterceptor())（不变） ======
        mv.visitTypeInsn(Opcodes.NEW, "com/qihoo/net/monitor/ContextInterceptor")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/qihoo/net/monitor/ContextInterceptor",
                "<init>",
                "()V",
                false)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                'okhttp3/OkHttpClient$Builder',
                "addInterceptor",
                '(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient$Builder;',
                false)


        // ====== 2. 插入 eventListenerFactory（静态内部类 Factory 处理） ======
        // 步骤1：创建静态内部类 Factory 实例（无需外部类）
        mv.visitTypeInsn(Opcodes.NEW, 'com/qihoo/net/monitor/NetworkEventListener$Factory')
        mv.visitInsn(Opcodes.DUP)  // 复制引用，用于构造函数调用
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                'com/qihoo/net/monitor/NetworkEventListener$Factory',
                "<init>",
                "()V",  // 静态内部类构造函数无参数
                false)  // 此时栈顶为 Factory 实例

        // 步骤2：调用 Builder.eventListenerFactory(Factory)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                'okhttp3/OkHttpClient$Builder',
                "eventListenerFactory",
                '(Lokhttp3/EventListener$Factory;)Lokhttp3/OkHttpClient$Builder;',
                false)
    }
}