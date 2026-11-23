/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools.base;


import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;




/**
 * Base class for all SPOON-based tools (Read-only operations).
 *
 * <p><strong>Architecture Rule:</strong> Spoon reads only from REWRITE: the model is built
 * from the Rewrite state (snapshot plus transaction overlay), never from the disk.
 * {@link #findEntry(String)} guarantees this via {@code ProjectManager.ensureSpoonModel}.
 * Tools of this layer must not access the filesystem directly.</p>
 *
 * <p>Responsibilities: code analysis (types, methods, fields), symbol search
 * (references, implementations), validation (pre-checks before edits), AST navigation.</p>
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public abstract class BaseReadTool extends BaseTool {

    /**
     * Constructs the tool with the given project manager.
     */
    protected BaseReadTool(ProjectManager manager) {
        super(manager);
    }
    /**
     * Spoon reads only from Rewrite: the model is rebuilt from the Rewrite state when needed,
     * before the entry is handed to the tool.
     */
    @Override
    protected ProjectEntry findEntry(String name) {
        return manager.ensureSpoonModel(super.findEntry(name).name());
    }

    // ==================== Fingerprint Validation =====================













    // ==================== Schema Helpers ====================







}
