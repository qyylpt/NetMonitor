package com.qihoo.net.monitor.plugin.asm

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

class OkHttpBuilderVisitor extends AdviceAdapter {
    private boolean inserted = false
    // 用于跟踪当前是否在处理一个Builder实例（解决多个Builder的问题）
    private boolean isInsideBuilderChain = false

    protected OkHttpBuilderVisitor(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc)
    }

    @Override
    void visitTypeInsn(int opcode, String type) {
        // 1. new OkHttpClient.Builder()
        if (opcode == Opcodes.NEW && type == 'okhttp3/OkHttpClient$Builder') {
            isInsideBuilderChain = true
            inserted = false  // 新Builder实例，重置插入标记
            println("跟踪到：直接创建Builder -> new OkHttpClient.Builder()")
        }
        // 2. new OkHttpClient()（用于后续调用newBuilder()）
        if (opcode == Opcodes.NEW && type == 'okhttp3/OkHttpClient') {
            println("跟踪到：创建OkHttpClient实例 -> 准备调用newBuilder()")
        }
        super.visitTypeInsn(opcode, type)
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
        if (opcode == Opcodes.INVOKEVIRTUAL &&
                owner == 'okhttp3/OkHttpClient' &&
                name == 'newBuilder' &&
                desc == '()Lokhttp3/OkHttpClient$Builder;') {

            isInsideBuilderChain = true  // 标记进入Builder链（newBuilder()返回的Builder）
            inserted = false             // 新Builder实例，重置插入标记
            println("跟踪到：通过newBuilder()创建Builder -> OkHttpClient().newBuilder()")
        }

        // 原有逻辑：跟踪Builder链中的方法调用（兼容所有场景的后续链式调用）
        if (owner == 'okhttp3/OkHttpClient$Builder') {
            println("Builder链方法：opcode=${opcode}, name=${name}, desc=${desc}, 跟踪中=${isInsideBuilderChain}")
        }

        // 仅处理处于Builder链中的方法（无论Builder是哪种方式创建的）
        if (isInsideBuilderChain && owner == 'okhttp3/OkHttpClient$Builder') {
            // 匹配build()方法，插入代码（所有场景共用）
            if (!inserted && name == 'build' && desc.startsWith('()Lokhttp3/OkHttpClient')) {
                println("匹配到build() -> 开始插入拦截器和监听器")
                insertBeforeBuild()
                inserted = true
            }

            // 更新跟踪状态：链式调用继续（返回值是Builder则继续跟踪）
            if (opcode == Opcodes.INVOKEVIRTUAL && desc.endsWith(')Lokhttp3/OkHttpClient$Builder;')) {
                isInsideBuilderChain = true
            }
            // build()后结束跟踪（Builder链终止）
            else if (name == 'build') {
                isInsideBuilderChain = false
            }
        }

        super.visitMethodInsn(opcode, owner, name, desc, isInterface)
    }

    private void insertBeforeBuild() {
        // 1. 插入 addInterceptor(new ContextInterceptor())
        mv.visitTypeInsn(Opcodes.NEW, 'com/qihoo/net/monitor/ContextInterceptor')
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                'com/qihoo/net/monitor/ContextInterceptor',
                '<init>',
                '()V',
                false)
        mv.visitTypeInsn(Opcodes.CHECKCAST, 'okhttp3/Interceptor')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                'okhttp3/OkHttpClient$Builder',
                'addInterceptor',
                '(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient$Builder;',
                false)

        // 2. 插入 eventListenerFactory(new NetworkEventListener.Factory())
        mv.visitTypeInsn(Opcodes.NEW, 'com/qihoo/net/monitor/NetworkEventListener$Factory')
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                'com/qihoo/net/monitor/NetworkEventListener$Factory',
                '<init>',
                '()V',
                false)
        mv.visitTypeInsn(Opcodes.CHECKCAST, 'okhttp3/EventListener$Factory')
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                'okhttp3/OkHttpClient$Builder',
                'eventListenerFactory',
                '(Lokhttp3/EventListener$Factory;)Lokhttp3/OkHttpClient$Builder;',
                false)
    }
}