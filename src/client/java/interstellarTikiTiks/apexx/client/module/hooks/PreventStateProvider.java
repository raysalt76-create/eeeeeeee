package interstellarTikiTiks.apexx.client.module.hooks;

public interface PreventStateProvider {

    boolean shouldPreventEscape();

    boolean shouldPreventTabOut();
}
