/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import de.jare.rewisp.ProjectManager;

/**
 * Base class of the project lifecycle and management tools (load, unload, reload, build refresh,
 * metadata, dirty status, reboot). These tools neither analyze nor edit code, so they trigger no
 * Spoon model rebuild ({@link BaseTool#findEntry} returns the entry unchanged) and do not take
 * the edit lock.
 *
 * @author Janusch Rentenatus
 */
public abstract class BaseProjectTool extends BaseTool {

    protected BaseProjectTool(ProjectManager manager) {
        super(manager);
    }
}