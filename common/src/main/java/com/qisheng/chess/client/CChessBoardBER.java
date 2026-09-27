package com.qisheng.chess.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qisheng.chess.tileentity.CChessTileEntity;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side {@link BlockEntityRenderer} for the chess block.
 *
 * <h2>Single-pass draw</h2>
 * One textured quad ~0.01 h above the top face, holding the live board
 * state (wood + grid + river + palace + piece discs + Chinese piece
 * labels + selection ring). The texture is repainted (and re-uploaded)
 * whenever the FEN / sdPlayer / state / selectPoint OR the viewer's
 * side flips — see {@link BoardSurfaceRenderer#paint}.
 *
 * <h2>Per-viewer rotation</h2>
 * The block has fixed orientation in world space (BLACK back rank at
 * the north edge, RED back rank at the south edge). For the surface:
 * <ul>
 *   <li><b>RED viewer</b> (camera on +Z side) — texture painted canonical:
 *       BLACK at the top, RED at the bottom (RED near the camera).</li>
 *   <li><b>BLACK viewer</b> (camera on -Z side) — texture painted mirrored
 *       both axes: BLACK at the bottom, RED at the top (BLACK near the
 *       camera). Exactly mirrors sitting on the opposite side of a
 *       physical board.</li>
 * </ul>
 * Because the labels are drawn into the texture at the screen position
 * the current viewer sees them (and never mirror the glyph itself),
 * every label stays upright from either perspective. A viewer crossing
 * from +Z to -Z (or vice versa) triggers one texture repaint — cheap
 * (~1 ms for the AWT glyph pass).
 *
 * <h2>Why this instead of {@code Font.drawInBatch} per piece</h2>
 * Doing the labels as a separate 3D pass was tried (v1.5) and pulled
 * in fragile billboard matrix math, fragile Y-offset vs. quad depth,
 * and 32+ draw calls per frame for what amounts to static text. AWT
 * + NativeImage is a one-shot render to texture that costs only on
 * content/viewer changes.
 */
public class CChessBoardBER implements BlockEntityRenderer<CChessTileEntity> {

    private final Map<BlockPos, BoardState> states = new HashMap<>();

    public CChessBoardBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(CChessTileEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockPos pos = be.getBlockPos();
        BoardSnapshot snap = BoardSurfaceCache.get(pos);
        if (snap == null) return;

        BoardState st = states.computeIfAbsent(pos, p -> createState());

        // ---- Viewer side ----
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        boolean viewerIsBlack = (camPos.z - (pos.getZ() + 0.5)) < 0.0;

        // ---- Repaint + upload only when content OR viewer side changed ----
        boolean contentChanged = !snap.sameContent(st.lastFen, st.lastSd,
                                                   st.lastState, st.lastSel);
        boolean viewerChanged = (viewerIsBlack != st.lastViewerIsBlack);
        if (contentChanged || viewerChanged) {
            BoardSurfaceRenderer.paint(st.image, snap.fen(), snap.selectPoint(),
                                      viewerIsBlack);
            st.texture.upload();
            st.lastFen = snap.fen();
            st.lastSd = snap.sdPlayer();
            st.lastState = snap.stateOrd();
            st.lastSel = snap.selectPoint();
            st.lastViewerIsBlack = viewerIsBlack;
        }

        // ---- Draw textured quad (UV is canonical, viewer already baked in) ----
        poseStack.pushPose();
        poseStack.translate(0.0, 1.01, 0.0);

        RenderSystem.setShaderTexture(0, st.textureId);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // CCW from above (looking down -Y).
        bb.vertex(poseStack.last().pose(), 0f, 0f, 0f).uv(0f, 0f).endVertex(); // NW
        bb.vertex(poseStack.last().pose(), 0f, 0f, 1f).uv(0f, 1f).endVertex(); // SW
        bb.vertex(poseStack.last().pose(), 1f, 0f, 1f).uv(1f, 1f).endVertex(); // SE
        bb.vertex(poseStack.last().pose(), 1f, 0f, 0f).uv(1f, 0f).endVertex(); // NE
        tess.end();

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        poseStack.popPose();
    }

    private BoardState createState() {
        NativeImage img = new NativeImage(BoardSurfaceRenderer.W,
                                          BoardSurfaceRenderer.H, true);
        BoardSurfaceRenderer.paint(img, CChessUtil.INIT, -1, false);
        DynamicTexture tex = new DynamicTexture(img);
        ResourceLocation id = new ResourceLocation(
                "qisheng_chess", "board_surface_" + System.nanoTime());
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return new BoardState(img, tex, id);
    }

    @Override
    public boolean shouldRenderOffScreen(CChessTileEntity be) { return false; }

    @Override
    public int getViewDistance() { return 64; }

    /** Per-board state. */
    private static class BoardState {
        final NativeImage image;
        final DynamicTexture texture;
        final ResourceLocation textureId;
        String lastFen = "";
        int lastSd = -1;
        int lastState = -1;
        int lastSel = -999;
        boolean lastViewerIsBlack = false;

        BoardState(NativeImage image, DynamicTexture texture, ResourceLocation id) {
            this.image = image;
            this.texture = texture;
            this.textureId = id;
        }
    }
}