package com.retromod.backport;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** Registers the 26.2 rendering API that 26.1.2 has under another shape: pipeline builders, bind groups, blending. */
final class RenderBackport {

    private static final String SHIM = "com/retromod/legacyapi/backport/render/";
    private static final String BUILDER = "com/mojang/blaze3d/pipeline/RenderPipeline$Builder";
    private static final String PIPELINE = "com/mojang/blaze3d/pipeline/RenderPipeline";
    private static final String BIND_GROUP = "com/mojang/blaze3d/pipeline/BindGroupLayout";
    private static final String FORMAT = "Lcom/mojang/blaze3d/vertex/VertexFormat;";
    private static final String MODE = "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;";
    private static final String BLEND_FUNCTION = "com/mojang/blaze3d/pipeline/BlendFunction";
    private static final String BLEND_FACTOR = "Lcom/mojang/blaze3d/platform/BlendFactor;";
    private static final String SOURCE_FACTOR = "Lcom/mojang/blaze3d/platform/SourceFactor;";

    private RenderBackport() {
    }

    /** Returns how many redirects it registered, for the report's counts. */
    static int register(RetromodTransformer t) {
        int count = 0;

        // Classes first: method redirects below are written with the 26.2 names and the transformer aliases them.
        // PrimitiveTopology has the same constants and members as 26.1.2's VertexFormat.Mode.
        t.registerClassRedirect("com/mojang/blaze3d/PrimitiveTopology", "com/mojang/blaze3d/vertex/VertexFormat$Mode");
        // BlendFactor is both the source and the destination enum in 26.2. The destination half is rebuilt in BlendBridge.
        t.registerClassRedirect("com/mojang/blaze3d/platform/BlendFactor", "com/mojang/blaze3d/platform/SourceFactor");
        t.registerClassRedirect(BIND_GROUP, SHIM + "BindGroupLayout");
        t.registerClassRedirect(BIND_GROUP + "$Builder", SHIM + "BindGroupLayout$Builder");
        t.registerClassRedirect(BIND_GROUP + "$UniformDescription", SHIM + "BindGroupLayout$UniformDescription");
        t.registerClassRedirect("net/minecraft/client/renderer/BindGroupLayouts", SHIM + "BindGroupLayouts");
        // GpuFormat names texel and vertex attribute layouts. The shim keeps the names and maps them to 26.1.2 types.
        t.registerClassRedirect("com/mojang/blaze3d/GpuFormat", SHIM + "GpuFormat");
        count += 7;

        // One 26.2 builder call becomes one helper call. The helper keeps what 26.1.2 wants in a single call.
        String bridge = SHIM + "RenderPipelineBridge";
        String builderType = "L" + BUILDER + ";";
        t.registerMethodRedirect(BUILDER, "withBindGroupLayout", "(L" + BIND_GROUP + ";)" + builderType, bridge,
                "withBindGroupLayout", "(" + builderType + "L" + SHIM + "BindGroupLayout;)" + builderType, true);
        t.registerMethodRedirect(BUILDER, "withVertexBinding", "(I" + FORMAT + ")" + builderType, bridge,
                "withVertexBinding", "(" + builderType + "I" + FORMAT + ")" + builderType, true);
        t.registerMethodRedirect(BUILDER, "withPrimitiveTopology", "(Lcom/mojang/blaze3d/PrimitiveTopology;)" + builderType,
                bridge, "withPrimitiveTopology", "(" + builderType + MODE + ")" + builderType, true);
        t.registerMethodRedirect(PIPELINE, "getVertexFormatBinding", "(I)" + FORMAT, bridge,
                "getVertexFormatBinding", "(L" + PIPELINE + ";I)" + FORMAT, true);
        t.registerMethodRedirect(PIPELINE, "getPrimitiveTopology", "()Lcom/mojang/blaze3d/PrimitiveTopology;", bridge,
                "getPrimitiveTopology", "(L" + PIPELINE + ";)" + MODE, true);
        count += 5;

        // VertexFormat.builder(int) is the 26.2 entry point, and addAttribute takes a GpuFormat where 26.1.2's
        // add takes an element. The mod keeps the real 26.1.2 builder and the helper picks the element.
        String vertexFormat = "com/mojang/blaze3d/vertex/VertexFormat";
        String vertexBuilder = "L" + vertexFormat + "$Builder;";
        String formatBridge = SHIM + "VertexFormatBridge";
        String gpuFormat = "Lcom/mojang/blaze3d/GpuFormat;";
        t.registerMethodRedirect(vertexFormat, "builder", "(I)" + vertexBuilder, formatBridge, "builder",
                "(I)" + vertexBuilder);
        String builderOwner = vertexFormat + "$Builder";
        String[][] attributes = {
            {"(Ljava/lang/String;" + gpuFormat + ")", "(" + vertexBuilder + "Ljava/lang/String;" + gpuFormat + ")"},
            {"(Ljava/lang/String;I" + gpuFormat + ")", "(" + vertexBuilder + "Ljava/lang/String;I" + gpuFormat + ")"},
            {"(Ljava/lang/String;" + gpuFormat + "I)", "(" + vertexBuilder + "Ljava/lang/String;" + gpuFormat + "I)"},
            {"(Ljava/lang/String;II" + gpuFormat + "I)", "(" + vertexBuilder + "Ljava/lang/String;II" + gpuFormat + "I)"},
        };
        for (String[] attribute : attributes) {
            t.registerMethodRedirect(builderOwner, "addAttribute", attribute[0] + vertexBuilder, formatBridge,
                    "addAttribute", attribute[1] + vertexBuilder, true);
        }
        count += 5;

        // 26.2 asks the render state whether improved transparency is on. 26.1.2 asks Minecraft, with the same test.
        String renderState = "net/minecraft/client/renderer/state/GameRenderState";
        t.registerMethodRedirect(renderState, "useShaderTransparency", "()Z", SHIM + "GameRenderStateBridge",
                "useShaderTransparency", "(L" + renderState + ";)Z", true);
        count++;

        // 26.2 draws the storage it is handed. 26.1.2 draws only the storage the dispatcher was built with.
        String dispatcher = "net/minecraft/client/renderer/feature/FeatureRenderDispatcher";
        String storage = "Lnet/minecraft/client/renderer/SubmitNodeStorage;";
        t.registerMethodRedirect(dispatcher, "renderAllFeatures", "(" + storage + ")V",
                SHIM + "FeatureRenderDispatcherBridge", "renderAllFeatures", "(L" + dispatcher + ";" + storage + ")V",
                true);
        count++;

        // 26.2 stores depth reversed: nearer is greater, the buffer clears to 0, and vanilla's default test is
        // GREATER_THAN_OR_EQUAL where 26.1.2's is LESS_THAN_OR_EQUAL. A mod's depth test written for 26.2 would
        // fail every pixel here, so each ordering constant becomes its mirror where the mod reads it, which also
        // keeps a value read back from a state equal to the constant it is compared with.
        String compareOp = "com/mojang/blaze3d/platform/CompareOp";
        String compareDesc = "L" + compareOp + ";";
        String[][] mirrors = {{"GREATER_THAN_OR_EQUAL", "LESS_THAN_OR_EQUAL"}, {"LESS_THAN_OR_EQUAL", "GREATER_THAN_OR_EQUAL"},
            {"GREATER_THAN", "LESS_THAN"}, {"LESS_THAN", "GREATER_THAN"}};
        for (String[] mirror : mirrors) {
            t.registerFieldRedirect(compareOp, mirror[0], compareDesc, compareOp, mirror[1], compareDesc);
            count++;
        }
        // The same reversal turns over the depth bias, which is passed as constants.
        t.registerConstructorRedirect("com/mojang/blaze3d/pipeline/DepthStencilState",
                "(" + compareDesc + "ZFF)V", SHIM + "DepthBridge", "depthStencilState",
                "(" + compareDesc + "ZFF)Lcom/mojang/blaze3d/pipeline/DepthStencilState;");
        count++;

        // The 26.2 constructors take BlendFactor for every argument. 26.1.2 wants source and destination enums.
        String blend = SHIM + "BlendBridge";
        t.registerConstructorRedirect(BLEND_FUNCTION, "(" + BLEND_FACTOR.repeat(4) + ")V", blend, "blendFunction",
                "(" + SOURCE_FACTOR.repeat(4) + ")L" + BLEND_FUNCTION + ";");
        t.registerConstructorRedirect(BLEND_FUNCTION, "(" + BLEND_FACTOR.repeat(2) + ")V", blend, "blendFunction",
                "(" + SOURCE_FACTOR.repeat(2) + ")L" + BLEND_FUNCTION + ";");
        count += 2;

        // A 26.2 picture-in-picture renderer extends the game class without a buffer source and overrides the
        // three-argument renderToTexture. The compat base gives it both on 26.1.2.
        t.registerSuperclassRebase("net/minecraft/client/gui/render/pip/PictureInPictureRenderer",
                SHIM + "PictureInPictureRendererCompat");
        count++;

        // 26.2 groups the sixteen dyed variants of an item or block in one ColorCollection field. 26.1.2 has one
        // field per color, so the field read becomes a getter that gathers them. colors.tsv lists each field.
        t.registerClassRedirect("net/minecraft/world/level/block/ColorCollection", SHIM + "ColorCollection");
        String collection = "L" + SHIM + "ColorCollection;";
        for (String[] row : BackportRegistration.rows("colors.tsv", 4)) {
            String holder = row[0].substring(row[0].lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            t.registerStandInStaticFieldBridge(row[0], row[1], SHIM + "ColorCollections", holder + "_" + row[1],
                    "()" + collection);
            count++;
        }
        count++;

        // The rebuilt classes themselves, read from Backmod's own jar and embedded in the mods that use them.
        for (String root : new String[] {"BindGroupLayouts", "RenderPipelineBridge", "BlendBridge",
            "PictureInPictureRendererCompat", "VertexFormatBridge",
            "GameRenderStateBridge", "ColorCollections",
            "FeatureRenderDispatcherBridge", "DepthBridge"}) {
            RebuiltApiClasses.registerTree(t, SHIM + root);
        }
        return count;
    }
}
