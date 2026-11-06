package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;











@Mixin(Keyboard.class)
public interface KeyboardCallbackAccessor {

    







    @Invoker("onKey")
    void invokeOnKey(long window, int action, KeyInput keyInput);
}
