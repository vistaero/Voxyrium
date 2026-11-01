package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.vk.render.VkSectionGeometryData;
import org.lwjgl.vulkan.VK10;

public final class VulkanMemoryTest {
    private static int failures;
    public static void main(String[] args) {
        run("create failure", () -> { VK10.failCreate=true; expectFailure(() -> new VkBuffer(context(),4096)); });
        run("memory-type failure", () -> { VK10.failMemoryType=true; expectFailure(() -> new VkBuffer(context(),4096)); });
        run("allocation failure x100", () -> { VK10.failAllocate=true; for(int i=0;i<100;i++) expectFailure(() -> new VkBuffer(context(),4096)); });
        run("bind failure x100", () -> { VK10.failBind=true; for(int i=0;i<100;i++) expectFailure(() -> new VkBuffer(context(),4096)); });
        run("successful deferred free x100", () -> {
            var ctx=context();
            for(int i=0;i<100;i++) { var b=new VkBuffer(ctx,4096); b.free(); }
            if(VK10.buffers.size()!=100) throw new AssertionError("destroyed before GPU retirement");
            ctx.free();
            ctx.free(); // Repeated teardown must not double-destroy explicit frees.
        });
        run("geometry fallback 2 GiB -> 1 GiB x20", () -> {
            VK10.allocationLimit=1L<<30;
            for(int i=0;i<20;i++) { var ctx=context(); var g=new VkSectionGeometryData(ctx,16,2L<<30); g.free(); ctx.waitIdleRetireAll(); }
        });
        run("geometry allocation exhausted x20", () -> {
            VK10.allocationLimit=1024;
            for(int i=0;i<20;i++) { var ctx=context(); expectFailure(() -> new VkSectionGeometryData(ctx,1,2L<<30)); ctx.waitIdleRetireAll(); }
        });
        run("geometry initialization failure", () -> {
            VK10.failFill=true; var ctx=context();
            expectFailure(() -> new VkSectionGeometryData(ctx,16,256L<<20)); ctx.waitIdleRetireAll();
        });
        run("context teardown after aborted subsystem initialization x20", () -> {
            for(int i=0;i<20;i++) {
                var ctx=context();
                // Completed allocations owned by earlier subsystems, plus a
                // partially constructed component that never returned to its owner.
                VK10.failAllocate=false;
                new VkBuffer(ctx,64L<<20); new VkBuffer(ctx,32L<<20); new VkBuffer(ctx,128L<<20);
                VK10.failAllocate=true;
                expectFailure(() -> new VkBuffer(ctx,2L<<30));
                ctx.free();
            }
        });
        if(failures!=0) throw new AssertionError(failures+" Vulkan memory scenarios leaked resources");
        System.out.println("PASS: all Vulkan memory scenarios returned to zero live buffers and bytes");
    }
    private static VkFrameCtx context() {
        var ctx=new VkFrameCtx(new VulkanContext());
        // Only the native driver is mocked. VkFrameCtx's actual command,
        // deferred-destruction and shutdown code executes against that driver.
        try {
            var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            var unsafe=(sun.misc.Unsafe)field.get(null);
            ctx.beginFrame((org.lwjgl.vulkan.VkCommandBuffer)unsafe.allocateInstance(org.lwjgl.vulkan.VkCommandBuffer.class));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        return ctx;
    }
    private static void expectFailure(Runnable action) {
        try { action.run(); } catch(RuntimeException expected) { return; }
        throw new AssertionError("expected injected failure");
    }
    private static void run(String name,Runnable action) {
        VK10.reset(); int initialCount=VkBuffer.getCount(); long initialBytes=VkBuffer.getTotalSize();
        try {
            action.run();
            if(!VK10.buffers.isEmpty() || !VK10.memories.isEmpty() || VkBuffer.getCount()!=initialCount || VkBuffer.getTotalSize()!=initialBytes)
                throw new AssertionError("live buffers="+VK10.buffers.size()+", allocations="+VK10.memories.size()+", bytes="+VK10.liveBytes()+", tracked count delta="+(VkBuffer.getCount()-initialCount));
            System.out.println("PASS: "+name);
        } catch(Throwable failure) { failures++; System.out.println("FAIL: "+name+": "+failure); }
    }
}
