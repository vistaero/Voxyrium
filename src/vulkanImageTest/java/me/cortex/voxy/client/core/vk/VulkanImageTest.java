package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Executes the compiled VkFrameHost, replacing only MC image accessors and
 * the barrier submission boundary. The production mask/layout/stage logic is
 * unchanged. --gpu forwards recorded barriers to the real Vulkan driver. */
public final class VulkanImageTest {
    public static int format;
    public static long imageHandle;
    private static Method transition;
    private static Class<?> host;
    private static boolean explicitStage;
    private static boolean gpu;
    private static int failures, checked;
    private static final boolean GENERAL = Boolean.getBoolean("voxy.vk.mcImagesGeneral");
    private record Barrier(int sourceStage, int targetStage, int sourceAccess, int targetAccess,
                           int oldLayout, int newLayout, int aspect, long image) {}
    private static final List<Barrier> barriers = new ArrayList<>();
    private static final int[] FORMATS = {VK_FORMAT_D16_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32,
            VK_FORMAT_D32_SFLOAT, VK_FORMAT_D16_UNORM_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_R8G8B8A8_UNORM};

    public static void main(String[] args) throws Exception {
        gpu = args.length == 1 && args[0].equals("--gpu");
        loadProductionHost();
        if (explicitStage) verifySsaoWiring();
        if (gpu) {
            VulkanImageGpu.run();
        } else {
            for (int f : FORMATS) {
                format = f;
                imageHandle = 42;
                checkBarrier(null, false);
                if (isDepth(f)) checkBarrier(null, true);
            }
            int expected = GENERAL ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            int sampled = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            try { sampled = (int) host.getMethod("mcSampledLayout").invoke(null); }
            catch (NoSuchMethodException ignored) {}
            require(sampled == expected, "sampled descriptor layout agrees with selected mode");
        }
        System.out.println("RESULT checks=" + checked + " failures=" + failures + " mode=" + (GENERAL ? "GENERAL" : "normal") + " driver=" + (gpu ? "real" : "recorded"));
        if (failures != 0) throw new AssertionError("Vulkan image validation failed: " + failures);
    }

    private static void verifySsaoWiring() throws Exception {
        var cls = new ClassNode();
        try (var stream = VulkanImageTest.class.getClassLoader().getResourceAsStream("me/cortex/voxy/client/core/vk/render/VkSSAO.class")) {
            if (stream == null) throw new IllegalStateException("Production SSAO class missing");
            new ClassReader(stream.readAllBytes()).accept(cls, 0);
        }
        int hostCalls = 0, offscreenChecks = 0;
        for (var method : cls.methods) {
            if (!method.name.equals("compute")) continue;
            for (var insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (call.name.equals("transitionMcImage")) {
                    hostCalls++;
                    require(call.desc.endsWith("ZIII)V") && constant(previous(call)) == VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            "actual SSAO caller supplies COMPUTE stage in both directions");
                }
                if (call.name.equals("transition") && call.owner.endsWith("/VkImage2D")) {
                    int[] args = new int[5]; var cursor = previous(call);
                    for (int i = 4; i >= 0; i--) { args[i] = constant(cursor); cursor = previous(cursor); }
                    if (cursor instanceof FieldInsnNode field && field.name.equals("depthStencil") && args[0] == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                        int writers = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
                        require((args[1] & writers) == writers, "SSAO offscreen depth covers early and late tests"); offscreenChecks++;
                    }
                    if (cursor instanceof FieldInsnNode field && field.name.equals("colourSSAO") && args[0] == VK_IMAGE_LAYOUT_GENERAL) {
                        int stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                        int access = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
                        require((args[1] & stages) == stages && (args[2] & access) == access,
                                "SSAO output reuse covers prior translucency writes and compositor reads"); offscreenChecks++;
                    }
                }
            }
        }
        require(hostCalls == 2 && offscreenChecks == 2, "SSAO transition wiring was exercised");
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        var node = instruction.getPrevious();
        while (node != null && node.getOpcode() < 0) node = node.getPrevious();
        return node;
    }

    private static int constant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode value) return value.operand;
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Integer number) return number;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        throw new IllegalStateException("Expected a constant stage/access argument; update SSAO wiring check");
    }

    public static boolean isDepth(int f) { return f != VK_FORMAT_R8G8B8A8_UNORM; }
    public static int aspect(int f) {
        if (!isDepth(f)) return VK_IMAGE_ASPECT_COLOR_BIT;
        return VK_IMAGE_ASPECT_DEPTH_BIT | (f == VK_FORMAT_D16_UNORM_S8_UINT || f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT_S8_UINT ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
    }
    public static int layout(int requested) { return GENERAL ? VK_IMAGE_LAYOUT_GENERAL : requested; }

    static void checkBarrier(VkCommandBuffer cmd, boolean compute) throws Exception {
        boolean depth = isDepth(format);
        int attachment = depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        int writer = depth ? VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        int writeAccess = depth ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        int readAccess = depth ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT : VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        int reader = compute ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        String label = "format=" + format + " consumer=" + (compute ? "compute(SSAO BETTER/BEST)" : "fragment") + " ";
        barriers.clear();
        invokeTransition(cmd, depth, attachment, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, reader);
        invokeTransition(cmd, depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, attachment, reader);
        require(barriers.size() == 2, label + "two barriers emitted");
        if (barriers.size() != 2) return;
        var forward = barriers.get(0);
        var reverse = barriers.get(1);
        for (var b : barriers) {
            require(b.aspect == aspect(format), label + "aspect expected=" + aspect(format) + " actual=" + b.aspect);
            require(b.image == imageHandle, label + "correct image handle");
        }
        require(forward.oldLayout == layout(attachment) && forward.newLayout == layout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL), label + "forward layouts");
        require(reverse.oldLayout == forward.newLayout && reverse.newLayout == forward.oldLayout, label + "reverse layouts");
        require((forward.sourceStage & writer) == writer && (forward.sourceAccess & writeAccess) == writeAccess, label + "producer writes covered");
        require((forward.targetStage & reader) != 0 && (forward.targetAccess & VK_ACCESS_SHADER_READ_BIT) != 0, label + "consumer reads covered");
        require((reverse.sourceStage & reader) != 0 && (reverse.sourceAccess & VK_ACCESS_SHADER_READ_BIT) != 0, label + "consumer reads complete before attachment reuse");
        require((reverse.targetStage & writer) == writer && (reverse.targetAccess & (readAccess | writeAccess)) == (readAccess | writeAccess), label + "attachment reuse covered");
    }

    static void invokeTransition(VkCommandBuffer cmd, boolean depth, int from, int to, int stage) throws Exception {
        // Explicit negative control for the native fixture: recreate the old
        // fragment-only contract while the actual consumer remains compute.
        if (Boolean.getBoolean("voxy.test.forceFragmentStage")) stage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        if (explicitStage) transition.invoke(null, cmd, null, depth, from, to, stage);
        else transition.invoke(null, cmd, null, depth, from, to);
    }

    public static void record(VkCommandBuffer cmd, int source, int target, int flags,
                              VkMemoryBarrier.Buffer memory, VkBufferMemoryBarrier.Buffer buffers,
                              VkImageMemoryBarrier.Buffer images) {
        for (int i = images.position(); i < images.limit(); i++) {
            var b = images.get(i);
            barriers.add(new Barrier(source, target, b.srcAccessMask(), b.dstAccessMask(), b.oldLayout(), b.newLayout(), b.subresourceRange().aspectMask(), b.image()));
        }
        if (gpu) vkCmdPipelineBarrier(cmd, source, target, flags, memory, buffers, images);
    }

    static void require(boolean condition, String message) {
        checked++;
        if (!condition) { failures++; System.err.println("FAIL " + message); }
    }

    private static void loadProductionHost() throws Exception {
        String name = "me.cortex.voxy.client.core.vk.render.VkFrameHost";
        byte[] original;
        try (var stream = VulkanImageTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new IllegalStateException("Production VkFrameHost class missing");
            original = stream.readAllBytes();
        }
        var cls = new ClassNode();
        new ClassReader(original).accept(cls, 0);
        String test = VulkanImageTest.class.getName().replace('.', '/');
        int accessors = 0, submissions = 0;
        for (var method : cls.methods) {
            if (method.name.equals("vkFormat")) {
                method.instructions.clear(); method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, test, "format", "I"));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            if (!method.name.equals("transitionMcImage")) continue;
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (call.name.equals("texture") && call.owner.endsWith("GpuTextureView")) {
                    var replacement = new InsnList(); replacement.add(new InsnNode(Opcodes.POP)); replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                    method.instructions.insertBefore(call, replacement); method.instructions.remove(call); accessors++;
                } else if (call.name.equals("vkImage")) {
                    var replacement = new InsnList(); replacement.add(new InsnNode(Opcodes.POP)); replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, test, "imageHandle", "J"));
                    method.instructions.insertBefore(call, replacement); method.instructions.remove(call); accessors++;
                } else if (call.owner.equals("org/lwjgl/vulkan/VK10") && call.name.equals("vkCmdPipelineBarrier")) {
                    call.owner = test; call.name = "record"; submissions++;
                }
            }
        }
        if (accessors != 2 || submissions != 1) throw new IllegalStateException("Accessor/driver boundary changed; update test instrumentation");
        // Stack shapes are preserved. Recompute max stack, retaining the original frames.
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); cls.accept(writer);
        host = new ClassLoader(VulkanImageTest.class.getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        transition = java.util.Arrays.stream(host.getMethods()).filter(m -> m.getName().equals("transitionMcImage"))
                .max(java.util.Comparator.comparingInt(Method::getParameterCount)).orElseThrow();
        explicitStage = transition.getParameterCount() == 6;
    }

    static int[] formats() { return FORMATS.clone(); }
}
