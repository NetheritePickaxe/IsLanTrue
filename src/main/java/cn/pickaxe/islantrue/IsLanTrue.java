package cn.pickaxe.islantrue;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IsLanTrue implements ClassFileTransformer {

    private static final Set<String> TARGET_CLASSES = Set.of(
        "net/minecraft/client/multiplayer/ServerData",
        "net/minecraft/client/network/ServerInfo"
    );

    private static final Set<String> DYNAMIC_TARGET_CLASSES = ConcurrentHashMap.newKeySet();

    public record PatchPlan(String className, FieldNode lanField, MethodNode getter) {}

    public record ProcessInfo(String pid, String displayName) {}
    public record InjectResult(boolean success, String message) {}

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[islantrue] Agent loaded");
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[islantrue] Agent attached");
        install(inst);
    }

    private static void install(Instrumentation inst) {
        discoverTargets(inst);
        inst.addTransformer(new IsLanTrue(), true);
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (shouldRetransform(clazz)) {
                try {
                    inst.retransformClasses(clazz);
                } catch (Exception e) {
                    System.err.println("[islantrue] Retransform " + clazz.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static boolean shouldTransform(String className) {
        if (className == null) return false;
        if (DYNAMIC_TARGET_CLASSES.contains(className) || TARGET_CLASSES.contains(className)) return true;
        return className.startsWith("net/minecraft/class_") && className.length() > 16;
    }

    private static boolean shouldRetransform(Class<?> clazz) {
        if (clazz == null || clazz.isArray() || clazz.isInterface()
            || clazz.isAnnotation() || clazz.isEnum() || clazz.isSynthetic())
            return false;
        return shouldTransform(clazz.getName().replace('.', '/'));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return transform(classfileBuffer, className);
    }

    public byte[] transform(byte[] classfileBuffer, String className) {
        if (!shouldTransform(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            cr.accept(classNode, 0);
            if (!hasValidStructure(classNode)) return null;
            PatchPlan plan = findPatchPlan(classNode);
            if (plan == null) return null;
            patchConstructors(classNode, plan);
            replaceGetter(plan.getter);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            classNode.accept(cw);
            System.out.println("[islantrue] Patched " + className);
            return cw.toByteArray();
        } catch (Exception e) {
            String msg = e.getMessage();
            System.err.println("[islantrue] Transform error " + className + ": "
                + (msg != null ? msg.replace('\n', ' ').replace('\r', ' ') : "no details"));
            return null;
        }
    }

    private static boolean hasValidStructure(ClassNode classNode) {
        int stringCount = 0, booleanCount = 0, otherCount = 0;
        for (FieldNode field : classNode.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
            switch (field.desc) {
                case "Ljava/lang/String;": stringCount++; break;
                case "Z": booleanCount++; break;
                default: if (field.desc.startsWith("L") || field.desc.startsWith("[")) otherCount++; break;
            }
        }
        return stringCount >= 2 && booleanCount >= 1 && otherCount >= 1;
    }

    private static PatchPlan findPatchPlan(ClassNode classNode) {
        MethodNode getter = findGetter(classNode);
        FieldNode lanField = findLanField(classNode, getter);
        if (lanField == null) return null;
        return new PatchPlan(classNode.name, lanField, getter);
    }

    private static MethodNode findGetter(ClassNode classNode) {
        for (MethodNode m : classNode.methods) {
            if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
            if (!"()Z".equals(m.desc)) continue;
            AbstractInsnNode insn = m.instructions.getFirst();
            while (insn != null && insn.getOpcode() == -1) insn = insn.getNext();
            if (insn == null || insn.getOpcode() != Opcodes.ALOAD) continue;
            if (((VarInsnNode) insn).var != 0) continue;
            insn = insn.getNext();
            while (insn != null && insn.getOpcode() == -1) insn = insn.getNext();
            if (insn == null || insn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!"Z".equals(((FieldInsnNode) insn).desc)) continue;
            insn = insn.getNext();
            while (insn != null && insn.getOpcode() == -1) insn = insn.getNext();
            if (insn == null || insn.getOpcode() != Opcodes.IRETURN) continue;
            return m;
        }
        return null;
    }

    private static FieldNode findLanField(ClassNode classNode, MethodNode getter) {
        if (getter != null) {
            for (AbstractInsnNode insn = getter.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.GETFIELD) {
                    FieldInsnNode gf = (FieldInsnNode) insn;
                    if (!"Z".equals(gf.desc) || !gf.owner.equals(classNode.name)) continue;
                    for (FieldNode f : classNode.fields) {
                        if (f.name.equals(gf.name) && f.desc.equals(gf.desc)) return f;
                    }
                }
            }
        }
        for (MethodNode m : classNode.methods) {
            if (!"<init>".equals(m.name)) continue;
            AbstractInsnNode insn = m.instructions.getFirst();
            while (insn != null) {
                if (insn.getOpcode() == Opcodes.ICONST_1) {
                    AbstractInsnNode next = insn.getNext();
                    while (next != null && next.getOpcode() == -1) next = next.getNext();
                    if (next != null && next.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode pf = (FieldInsnNode) next;
                        if ("Z".equals(pf.desc) && pf.owner.equals(classNode.name)) {
                            for (FieldNode f : classNode.fields) {
                                if (f.name.equals(pf.name) && f.desc.equals(pf.desc)) return f;
                            }
                        }
                    }
                }
                insn = insn.getNext();
            }
        }
        return null;
    }

    private static void patchConstructors(ClassNode classNode, PatchPlan plan) {
        for (MethodNode method : classNode.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if ("<init>".equals(mi.name)) {
                        InsnList insert = new InsnList();
                        insert.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insert.add(new InsnNode(Opcodes.ICONST_1));
                        insert.add(new FieldInsnNode(Opcodes.PUTFIELD, plan.className, plan.lanField.name, plan.lanField.desc));
                        method.instructions.insert(insn, insert);
                        break;
                    }
                }
            }
        }
    }

    private static void replaceGetter(MethodNode getter) {
        if (getter == null) return;
        getter.instructions.clear();
        getter.tryCatchBlocks.clear();
        getter.localVariables = null;
        getter.instructions.add(new InsnNode(Opcodes.ICONST_1));
        getter.instructions.add(new InsnNode(Opcodes.IRETURN));
        getter.maxLocals = 1;
        getter.maxStack = 1;
    }

    private static void discoverTargets(Instrumentation inst) {
        ClassLoader loader = inst.getClass().getClassLoader();
        try {
            Class<?> mc = Class.forName("net.minecraft.client.MinecraftClient", false, loader);
            for (Class<?> inner : mc.getDeclaredClasses()) {
                if (looksLikeServerData(inner)) {
                    DYNAMIC_TARGET_CLASSES.add(inner.getName().replace('.', '/'));
                }
            }
        } catch (Exception e) {
            System.err.println("[islantrue] MinecraftClient scan: " + e.getClass().getSimpleName());
        }
        try {
            for (String pkg : List.of("net.minecraft.client.multiplayer", "net.minecraft.client.network", "net.minecraft.server")) {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    String name = c.getName().replace('.', '/');
                    if (name.startsWith(pkg + "/") && looksLikeServerData(c)) {
                        DYNAMIC_TARGET_CLASSES.add(name);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[islantrue] Package scan: " + e.getClass().getSimpleName());
        }
        if (!DYNAMIC_TARGET_CLASSES.isEmpty()) {
            System.out.println("[islantrue] Targets: " + String.join(", ", DYNAMIC_TARGET_CLASSES));
        }
    }

    private static boolean looksLikeServerData(Class<?> clazz) {
        if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotation() || clazz.isSynthetic()) return false;
        int strings = 0, bools = 0, others = 0;
        for (var f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            var t = f.getType();
            if (t == String.class) strings++;
            else if (t == boolean.class || t == Boolean.class) bools++;
            else if (!t.isPrimitive()) others++;
        }
        return strings >= 2 && bools >= 1 && others >= 1;
    }

    public static void main(String[] args) {
        var injector = new PlatformInjector();
        if (args.length == 0) {
            autoScanAndInject(injector);
            return;
        }
        switch (args[0]) {
            case "--help", "-h" -> printHelp();
            case "--list" -> listProcesses(injector);
            case "--pid" -> {
                if (args.length > 1) {
                    injectByPid(injector, args[1]);
                } else {
                    System.err.println("Missing PID. Usage: --pid <PID>");
                    System.exit(1);
                }
            }
            default -> {
                System.err.println("Unknown option: " + args[0]);
                System.err.println("Use --help for usage.");
                System.exit(1);
            }
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar islantrue.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  (none)          Auto-inject first Minecraft process");
        System.out.println("  --list          List detectable Minecraft processes");
        System.out.println("  --pid <PID>     Inject into specific process");
        System.out.println("  --help, -h      Show this help");
        System.out.println();
        System.out.println("Agent: java -javaagent:islantrue.jar -jar <server.jar>");
    }

    private static void listProcesses(PlatformInjector injector) {
        var processes = injector.scanProcesses();
        if (processes.isEmpty()) {
            System.out.println("No Minecraft processes detected.");
            return;
        }
        System.out.println("Found " + processes.size() + " process(es):");
        for (int i = 0; i < processes.size(); i++) {
            var p = processes.get(i);
            System.out.printf("  [%d] %s%n", i + 1, p.pid());
            if (!p.displayName().isEmpty() && !p.displayName().equals(p.pid())) {
                System.out.printf("      %s%n", p.displayName());
            }
        }
    }

    private static void injectByPid(PlatformInjector injector, String pid) {
        var agentBytes = readSelfJar();
        if (agentBytes == null) return;
        var result = injector.inject(pid, agentBytes);
        System.out.println(result.message());
    }

    private static void autoScanAndInject(PlatformInjector injector) {
        var processes = injector.scanProcesses();
        if (processes.isEmpty()) {
            System.out.println("No Minecraft processes detected.");
            System.out.println("Start Minecraft first, or use --pid <PID>");
            return;
        }
        var target = processes.get(0);
        System.out.println("Found: " + target.pid());
        var agentBytes = readSelfJar();
        if (agentBytes == null) return;
        var result = injector.inject(target.pid(), agentBytes);
        System.out.println(result.message());
    }

    private static byte[] readSelfJar() {
        try {
            var cs = IsLanTrue.class.getProtectionDomain().getCodeSource();
            if (cs == null) {
                System.err.println("[islantrue] Cannot locate self JAR (no CodeSource)");
                return null;
            }
            return Files.readAllBytes(Path.of(cs.getLocation().toURI()));
        } catch (Exception e) {
            System.err.println("[islantrue] Cannot read self JAR: " + e.getMessage());
            return null;
        }
    }

    public static class PlatformInjector {

        private static final String[] MINECRAFT_PATTERNS = {
            "minecraft", ".minecraft", "mixin.bootstrap", "net.minecraft.client.main.Main"
        };

        public List<ProcessInfo> scanProcesses() {
            List<ProcessInfo> results = new ArrayList<>();
            try {
                ProcessHandle.allProcesses().forEach(proc -> {
                    var cmdLine = proc.info().commandLine().orElse("");
                    for (var pattern : MINECRAFT_PATTERNS) {
                        if (cmdLine.toLowerCase(Locale.ROOT).contains(pattern)) {
                            results.add(new ProcessInfo(String.valueOf(proc.pid()),
                                proc.info().command().orElse(String.valueOf(proc.pid()))));
                            return;
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("[islantrue] Process scan error: " + e.getMessage());
            }
            return results;
        }

        public InjectResult inject(String targetPid, byte[] agentJarBytes) {
            try {
                var vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
                var attach = vmClass.getMethod("attach", String.class);
                var loadAgent = vmClass.getMethod("loadAgent", String.class);
                var detach = vmClass.getMethod("detach");
                Object vm = attach.invoke(null, targetPid);
                try {
                    var tempJar = File.createTempFile("islantrue-agent", ".jar");
                    tempJar.deleteOnExit();
                    Files.write(tempJar.toPath(), agentJarBytes);
                    try {
                        loadAgent.invoke(vm, tempJar.getAbsolutePath());
                        return new InjectResult(true, "Injected into " + targetPid);
                    } finally {
                        tempJar.delete();
                    }
                } finally {
                    try {
                        detach.invoke(vm);
                    } catch (Exception e) {
                        System.err.println("[islantrue] Detach warning: " + e.getMessage());
                    }
                }
            } catch (ClassNotFoundException e) {
                return new InjectResult(false, "Attach API unavailable (JRE detected). Use full JDK.");
            } catch (NoSuchMethodException e) {
                return new InjectResult(false, "JDK version mismatch: " + e.getMessage());
            } catch (Exception e) {
                var cause = e.getCause() != null ? e.getCause() : e;
                return new InjectResult(false, "Injection error: " + cause.getMessage());
            }
        }
    }
}
