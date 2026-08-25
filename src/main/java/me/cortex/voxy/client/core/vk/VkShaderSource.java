package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;

import java.util.LinkedHashMap;
import java.util.Map;

//Loads a Voxy shader asset for the Vulkan backend: expands #imports via the
// shared ShaderLoader, forces a Vulkan-capable #version, and injects #defines
// (binding remaps, feature flags) right after the version/extension header —
// the same define-injection model the GL Shader builder uses. VOXY_VULKAN is
// added by ShadercCompiler itself, activating the guards in shared sources.
public final class VkShaderSource {
    //Shader printf debugging is a GL-path feature (PrintfInjector rewrites the calls);
    // on VK the statements are stripped so the shared sources stay single-source.
    private static final java.util.regex.Pattern PRINTF = java.util.regex.Pattern.compile("printf\\s*\\([^;]*?\\)\\s*;", java.util.regex.Pattern.DOTALL);

    public static String load(String id, Map<String, String> defines) {
        String src = PRINTF.matcher(ShaderLoader.parse(id)).replaceAll("");
        StringBuilder header = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean versionDone = false;
        for (String line : src.split("\n", -1)) {
            String trimmed = line.trim();
            if (!versionDone && trimmed.startsWith("#version")) {
                //Force a Vulkan-legal version (some fullscreen shaders are #version 330)
                header.append("#version 460 core\n");
                versionDone = true;
                continue;
            }
            if (!versionDone || trimmed.startsWith("#extension")) {
                header.append(line).append('\n');
                continue;
            }
            body.append(line).append('\n');
        }
        StringBuilder out = new StringBuilder(header);
        if (!versionDone) {
            out.insert(0, "#version 460 core\n");
        }
        for (var e : defines.entrySet()) {
            out.append("#define ").append(e.getKey());
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                out.append(' ').append(e.getValue());
            }
            out.append('\n');
        }
        out.append(body);
        return out.toString();
    }

    public static DefineBuilder defs() {
        return new DefineBuilder();
    }

    public static final class DefineBuilder {
        private final Map<String, String> map = new LinkedHashMap<>();

        public DefineBuilder def(String k) { this.map.put(k, ""); return this; }
        public DefineBuilder def(String k, int v) { this.map.put(k, Integer.toString(v)); return this; }
        public DefineBuilder def(String k, float v) { this.map.put(k, Float.toString(v)); return this; }
        public DefineBuilder def(String k, String v) { this.map.put(k, v); return this; }
        public DefineBuilder defIf(String k, boolean condition) { if (condition) this.map.put(k, ""); return this; }

        /** USE_ZERO_ONE_DEPTH / USE_REVERSE_Z from render properties. */
        public DefineBuilder props(me.cortex.voxy.client.core.RenderProperties properties) {
            this.defIf("USE_ZERO_ONE_DEPTH", properties.isZero2One());
            this.defIf("USE_REVERSE_Z", properties.isReverseZ());
            return this;
        }

        public Map<String, String> build() { return this.map; }
    }
}
