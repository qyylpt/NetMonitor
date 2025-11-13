package com.qihoo.net.monitor.plugin.http.asm

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AdviceAdapter

/**
 * ASM MethodVisitor 用于修改方法中的字节码
 */
class HttpUrlConnectionMethodVisitor extends AdviceAdapter {

    protected HttpUrlConnectionMethodVisitor(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc)
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        // 拦截 URL.openConnection() 方法调用
        if (opcode == Opcodes.INVOKEVIRTUAL
                && owner == "java/net/URL"
                && name == "openConnection"
                && (desc == "()Ljava/net/URLConnection;" || desc == "(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {

            // 先执行原始的 openConnection 调用
            super.visitMethodInsn(opcode, owner, name, desc, itf)

            // 替换结果为 MonitoredHttpURLConnection 代理类
            // 生成字节码：new MonitoredHttpURLConnection(url, originalConnection)
            mv.visitTypeInsn(Opcodes.NEW, "com/qihoo/net/monitor/httpurl/MonitoredHttpURLConnection")
            mv.visitInsn(Opcodes.DUP)
            // 加载当前 URL 对象（this）作为第一个参数
            mv.visitVarInsn(Opcodes.ALOAD, 0)  // 假设 URL 对象在局部变量表索引 0（需根据实际场景调整）
            // 加载原始连接对象（openConnection 的返回值）作为第二个参数
            mv.visitInsn(Opcodes.SWAP)
            // 调用构造方法
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "com/qihoo/net/monitor/httpurl/MonitoredHttpURLConnection",
                    "<init>",
                    "(Ljava/net/URL;Ljava/net/HttpURLConnection;)V",
                    false)
            return
        }

        // 拦截 HttpURLConnection 构造和相关方法（可选扩展）
        if (owner == "java/net/HttpURLConnection" && name == "<init>") {
            // 可在这里添加对构造函数的监控逻辑
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }
}