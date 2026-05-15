package dev.nekoobfuscator.transforms.jvm.internal;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

public final class JvmPassBytecode {
    private JvmPassBytecode() {}

    public static void pushInt(InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    public static void pushLong(InsnList insns, long value) {
        if (value == 0L) {
            insns.add(new InsnNode(Opcodes.LCONST_0));
        } else if (value == 1L) {
            insns.add(new InsnNode(Opcodes.LCONST_1));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    public static long mix(long state, long value) {
        long z = state + value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
