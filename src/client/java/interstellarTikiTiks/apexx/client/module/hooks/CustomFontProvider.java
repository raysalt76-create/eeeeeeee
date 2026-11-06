package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;










public interface CustomFontProvider {

    


    
    boolean isMsdfGlobalActive();

    
    Identifier activeMsdfFontId();

    
    boolean isGlobalFontActive();

    
    Identifier activeFontId();

    
    float globalFontScale();

    

    

    


    
    float clientFontScale();

    
    boolean clientShadowFor(boolean shadow);

    
    boolean globalShadowFor(boolean shadow);

    
    boolean msdfEnabled();

    
    int measureTextWidth(TextRenderer textRenderer, String text);

    
    float measureTextHeight(TextRenderer textRenderer);

    



    void renderText(DrawContext context, TextRenderer textRenderer, String text,
                    float x, float y, int color, boolean shadow);
}
