package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class PearlModuleExecuteEvent implements Event {
    private final String moduleName;

    public PearlModuleExecuteEvent(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getModuleName() {
        return moduleName;
    }
}
