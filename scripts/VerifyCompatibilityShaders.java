import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER;

/** Driver-level regression check. Run with LWJGL core/OpenGL/GLFW and native JARs on the classpath.
 * Pass generated resource directories (or src/main/resources) as arguments.
 * Uses a hidden context; never opens or changes a Minecraft world.
 */
public class VerifyCompatibilityShaders {
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#import <voxy:([^>]+)>\\s*$");
    private static int checked;

    private static String expand(Path root, String name) throws Exception {
        String source = Files.readString(root.resolve("assets/voxy/shaders/" + name)).replace("\r\n", "\n");
        var matcher = IMPORT.matcher(source);
        var result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result,
                java.util.regex.Matcher.quoteReplacement(expand(root, matcher.group(1))));
        matcher.appendTail(result);
        return result.toString();
    }

    private static void check(Path root, String name, int stage, String defines) throws Exception {
        String source = expand(root, name).replaceAll("(?m)^#version[^\\n]*", "");
        // Match Voxy's production (non-debug) PrintfDebugUtil processor.
        if (name.startsWith("lod/hierarchical/")) source = source.replace("printf", "//printf");
        int shader = glCreateShader(stage);
        try {
            glShaderSource(shader, "#version 460 core\n" + defines + "\n" + source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new AssertionError(root + "/" + name + "\n" + defines + glGetShaderInfoLog(shader));
            checked++;
        } finally { glDeleteShader(shader); }
    }

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(32, 32, "Voxy shader verification", 0, 0);
        if (window == 0) throw new IllegalStateException("Unable to create OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("Driver: " + glGetString(GL_VERSION));
            for (String arg : args) {
                Path root = Path.of(arg);
                for (String defines : new String[]{"", "#define EMIT_COLOUR\n", "#define EMIT_COLOUR\n#define HAS_FOG\n",
                        "#define EMIT_COLOUR\n#define HAS_FADE\n", "#define EMIT_COLOUR\n#define HAS_FOG\n#define HAS_FADE\n"})
                    check(root, "post/blit_texture_depth_cutout.frag", GL_FRAGMENT_SHADER, defines);
                for (String defines : new String[]{"", "#define BETTER_SSAO\n#define SSAO_STEPS 12\n", "#define BETTER_SSAO\n#define SSAO_STEPS 24\n"})
                    check(root, "post/ssao.comp", GL_COMPUTE_SHADER, defines);
                if (Files.exists(root.resolve("assets/voxy/shaders/bakery/bufferreorder.comp")))
                    check(root, "bakery/bufferreorder.comp", GL_COMPUTE_SHADER,
                            "#define WIDTH 16\n#define HEIGHT 16\n#define COLOUR_IN_BINDING 0\n#define DEPTH_IN_BINDING 1\n#define STENCIL_IN_BINDING 2\n#define BUFFER_OUT_BINDING 3\n#define META_IN_BINDING 4\n");
                if (arg.contains("1.21.2/") || arg.contains("1.21.3/"))
                    check(root, "lod/hierarchical/traversal_dev.comp", GL_COMPUTE_SHADER,
                            "#define MAX_ITERATIONS 5\n#define LOCAL_SIZE_BITS 5\n#define REQUEST_QUEUE_SIZE 256\n#define HIZ_BINDING 0\n"
                            + "#define SCENE_UNIFORM_BINDING 1\n#define REQUEST_QUEUE_BINDING 2\n#define RENDER_QUEUE_BINDING 3\n#define NODE_DATA_BINDING 4\n"
                            + "#define NODE_QUEUE_INDEX_BINDING 5\n#define NODE_QUEUE_META_BINDING 6\n#define NODE_QUEUE_SOURCE_BINDING 7\n#define NODE_QUEUE_SINK_BINDING 8\n");
                System.out.println("PASS " + root);
            }
            System.out.println("PASS " + checked + " shader variants");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
