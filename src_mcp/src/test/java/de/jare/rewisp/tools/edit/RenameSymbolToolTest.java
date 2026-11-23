/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for RenameSymbolTool - testing tool metadata only.
 * Integration tests require full project setup and are out of scope for now.
 * 
 * @author Janusch Rentenatus
 */
class RenameSymbolToolTest {

    @Test
    void testToolName() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        RenameSymbolTool tool = new RenameSymbolTool(projectManager, editManager, RewriteTestSupport.newEngine());
        
        assertEquals("rename_symbol", tool.toolName());
    }

    @Test
    void testToolDescription() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        RenameSymbolTool tool = new RenameSymbolTool(projectManager, editManager, RewriteTestSupport.newEngine());
        
        String description = tool.toolDescription();
        assertTrue(description.contains("REWRITE"));
        assertTrue(description.contains("Rename a class"));
        assertTrue(description.contains("OpenRewrite"));
    }

    @Test
    void testToolProperties() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        RenameSymbolTool tool = new RenameSymbolTool(projectManager, editManager, RewriteTestSupport.newEngine());
        
        var props = tool.toolProperties();
        
        assertNotNull(props);
        assertEquals(7, props.size());
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("className"));
        assertTrue(props.containsKey("oldName"));
        assertTrue(props.containsKey("newName"));
        assertTrue(props.containsKey("scope"));
        assertTrue(props.containsKey("updateCallers"));
    }

    @Test
    void testToolRequired() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        RenameSymbolTool tool = new RenameSymbolTool(projectManager, editManager, RewriteTestSupport.newEngine());
        
        var required = tool.toolRequired();
        
        assertNotNull(required);
        assertEquals(4, required.size());
        assertTrue(required.contains("name"));
        assertTrue(required.contains("className"));
        assertTrue(required.contains("oldName"));
        assertTrue(required.contains("newName"));
    }
}
