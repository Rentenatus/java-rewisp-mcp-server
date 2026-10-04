/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for MoveClassTool - testing tool metadata only.
 * Integration tests require full project setup and are out of scope for now.
 * 
 * @author Janusch Rentenatus
 */
class MoveClassToolTest {

    @Test
    void testToolName() {
        // Create tool with legacy constructor
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        MoveClassTool tool = new MoveClassTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        assertEquals("move_class", tool.toolName());
    }

    @Test
    void testToolDescription() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        MoveClassTool tool = new MoveClassTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        String description = tool.toolDescription();
        assertTrue(description.contains("REWRITE"));
        assertTrue(description.contains("Move a class"));
        assertTrue(description.contains("OpenRewrite"));
    }

    @Test
    void testToolProperties() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        MoveClassTool tool = new MoveClassTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        var props = tool.toolProperties();
        
        assertNotNull(props);
        assertEquals(4, props.size());
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("className"));
        assertTrue(props.containsKey("newPackage"));
        assertTrue(props.containsKey("dryRun"));
    }

    @Test
    void testToolRequired() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        MoveClassTool tool = new MoveClassTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        var required = tool.toolRequired();
        
        assertNotNull(required);
        assertEquals(3, required.size());
        assertTrue(required.contains("name"));
        assertTrue(required.contains("className"));
        assertTrue(required.contains("newPackage"));
    }
}
