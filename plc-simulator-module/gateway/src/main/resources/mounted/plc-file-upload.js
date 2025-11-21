/**
 * Enhanced PLC Simulator - File Upload Enhancement
 *
 * This script adds a file upload button to the device configuration form.
 * Auto-loaded when viewing Enhanced PLC Simulator device config pages.
 */

(function() {
    'use strict';

    console.log('=== PLC SIMULATOR SCRIPT LOADED ===');
    console.log('Current URL:', window.location.href);
    console.log('Document ready state:', document.readyState);

    // Prevent multiple injections
    if (window.plcSimulatorUploadInjected) {
        console.log('PLC Simulator: Upload UI already injected');
        return;
    }
    window.plcSimulatorUploadInjected = true;

    console.log('PLC Simulator: Initializing file upload enhancement...');

    // Wait for DOM to be ready
    if (document.readyState === 'loading') {
        console.log('DOM still loading, adding DOMContentLoaded listener');
        document.addEventListener('DOMContentLoaded', initFileUpload);
    } else {
        console.log('DOM already ready, initializing immediately');
        initFileUpload();
    }

    function initFileUpload() {
        console.log('=== initFileUpload() called ===');

        // First, inject clickable link for "Upload PLC Program" field
        console.log('Attempting to inject Upload PLC Program clickable link...');
        injectUploadProgramLink();

        // Then, inject the prominent upload page button
        console.log('Attempting to inject Upload Page button...');
        injectUploadPageButton();

        // Then, try to inject the clickable Program Manager link
        console.log('Attempting to inject Program Manager link...');
        injectProgramManagerLink();

        // Find the textarea for file content
        console.log('Looking for file content textarea...');
        const textarea = findTextareaByLabel('PLC File Content') || findTextareaByLabel('File Content (Internal)');

        if (!textarea) {
            console.log('PLC Simulator: File content textarea not found, retrying in 1s...');
            setTimeout(initFileUpload, 1000);
            return;
        }

        console.log('Found textarea:', textarea);

        // Check if we already added the upload button
        if (textarea.parentElement.querySelector('.plc-upload-button')) {
            console.log('PLC Simulator: Upload button already exists');
            return;
        }

        // Create file upload UI
        console.log('Creating upload UI...');
        createUploadUI(textarea);
        console.log('PLC Simulator: File upload UI injected successfully');
    }

    /**
     * Inject clickable link for "Upload PLC Program" field.
     * This converts the plain text field into a clickable button that opens the upload page.
     */
    function injectUploadProgramLink() {
        console.log('=== injectUploadProgramLink() called ===');

        // Find the "Upload PLC Program" field by looking for the label with emoji
        const labels = document.querySelectorAll('label');
        console.log('Found', labels.length, 'labels on page');

        let uploadProgramField = null;
        let uploadProgramContainer = null;

        for (const label of labels) {
            const labelText = label.textContent.trim();
            console.log('Checking label:', labelText.substring(0, 50));

            // Match "Upload PLC Program" or the emoji
            if (labelText.includes('Upload PLC Program') || labelText.includes('📁 Upload PLC Program')) {
                console.log('FOUND matching label:', labelText);
                uploadProgramContainer = label.closest('.form-group, .field-container, div');
                console.log('Container found:', !!uploadProgramContainer);

                if (uploadProgramContainer) {
                    uploadProgramField = uploadProgramContainer.querySelector('input[type="text"]');
                    console.log('Input field found:', !!uploadProgramField);
                    if (uploadProgramField) {
                        console.log('Input field value:', uploadProgramField.value);
                        break;
                    }
                }
            }
        }

        if (!uploadProgramField || !uploadProgramContainer) {
            console.log('PLC Simulator: Upload PLC Program field not found - link injection skipped');
            return;
        }

        console.log('Found uploadProgramField, checking if already injected...');

        // Check if link already injected
        if (uploadProgramContainer.querySelector('.plc-upload-program-link')) {
            console.log('Upload program link already injected, skipping');
            return;
        }

        console.log('No existing link found, proceeding with injection...');

        // Get the upload page URL from the field or use default
        const baseUrl = window.location.origin;
        const uploadPageUrl = uploadProgramField.value || '/res/plcsimulator/simple-upload.html';
        const fullUploadUrl = uploadPageUrl.startsWith('http') ? uploadPageUrl : `${baseUrl}${uploadPageUrl}`;

        console.log('Upload page URL:', fullUploadUrl);

        // Create clickable button container
        const buttonContainer = document.createElement('div');
        buttonContainer.className = 'plc-upload-program-link';
        buttonContainer.style.cssText = 'margin-bottom: 16px; padding: 16px; background: linear-gradient(135deg, #e7f3ff 0%, #f0f9ff 100%); border: 2px solid #0066cc; border-radius: 8px; box-shadow: 0 2px 8px rgba(0, 102, 204, 0.1);';

        // Create the clickable button
        const uploadButton = document.createElement('a');
        uploadButton.href = fullUploadUrl;
        uploadButton.target = '_blank';
        uploadButton.style.cssText = `
            display: inline-block;
            padding: 14px 28px;
            background: linear-gradient(135deg, #0066cc 0%, #0052a3 100%);
            color: white;
            text-decoration: none;
            border-radius: 6px;
            font-weight: 600;
            font-size: 16px;
            transition: all 0.3s ease;
            box-shadow: 0 4px 12px rgba(0, 102, 204, 0.3);
            border: none;
            cursor: pointer;
        `;
        uploadButton.innerHTML = '📤 Open File Upload Page ↗';

        // Enhanced hover effects
        uploadButton.addEventListener('mouseover', function() {
            this.style.background = 'linear-gradient(135deg, #0052a3 0%, #003d7a 100%)';
            this.style.boxShadow = '0 6px 16px rgba(0, 102, 204, 0.4)';
            this.style.transform = 'translateY(-2px)';
        });
        uploadButton.addEventListener('mouseout', function() {
            this.style.background = 'linear-gradient(135deg, #0066cc 0%, #0052a3 100%)';
            this.style.boxShadow = '0 4px 12px rgba(0, 102, 204, 0.3)';
            this.style.transform = 'translateY(0)';
        });

        // Help text with instructions
        const helpText = document.createElement('div');
        helpText.style.cssText = 'margin-top: 12px; font-size: 14px; color: #334155; line-height: 1.6;';
        helpText.innerHTML = `
            <strong style="color: #0066cc;">Click the button above to:</strong>
            <ul style="margin: 8px 0 0 20px; color: #475569;">
                <li>Upload your Rockwell L5K file using drag-and-drop interface</li>
                <li>File is automatically saved and loaded into this device</li>
                <li>Return here and save the device configuration to persist changes</li>
            </ul>
        `;

        // Add URL reference with copy functionality
        const urlSection = document.createElement('div');
        urlSection.style.cssText = 'margin-top: 12px; padding: 10px; background: white; border-radius: 6px; border: 1px solid #cbd5e1;';

        const urlLabel = document.createElement('div');
        urlLabel.style.cssText = 'font-size: 12px; color: #64748b; margin-bottom: 6px; font-weight: 500;';
        urlLabel.textContent = 'Direct URL:';

        const urlDisplay = document.createElement('div');
        urlDisplay.style.cssText = 'display: flex; align-items: center; gap: 8px;';

        const urlCode = document.createElement('code');
        urlCode.style.cssText = 'flex: 1; background: #1e293b; color: #60a5fa; padding: 8px 12px; border-radius: 4px; font-size: 12px; font-family: "Courier New", monospace; overflow-x: auto; white-space: nowrap;';
        urlCode.textContent = fullUploadUrl;

        const copyButton = document.createElement('button');
        copyButton.type = 'button';
        copyButton.textContent = '📋 Copy';
        copyButton.style.cssText = 'padding: 6px 14px; background: #475569; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500; transition: all 0.2s; white-space: nowrap;';

        copyButton.addEventListener('mouseover', function() {
            this.style.background = '#64748b';
        });
        copyButton.addEventListener('mouseout', function() {
            if (this.textContent === '📋 Copy') {
                this.style.background = '#475569';
            }
        });

        copyButton.onclick = function(e) {
            e.preventDefault();
            navigator.clipboard.writeText(fullUploadUrl).then(() => {
                this.textContent = '✅ Copied!';
                this.style.background = '#10b981';
                setTimeout(() => {
                    this.textContent = '📋 Copy';
                    this.style.background = '#475569';
                }, 2000);
            }).catch(err => {
                console.error('Copy failed:', err);
                // Fallback for older browsers
                const textarea = document.createElement('textarea');
                textarea.value = fullUploadUrl;
                textarea.style.position = 'fixed';
                textarea.style.opacity = '0';
                document.body.appendChild(textarea);
                textarea.select();
                try {
                    document.execCommand('copy');
                    this.textContent = '✅ Copied!';
                    this.style.background = '#10b981';
                    setTimeout(() => {
                        this.textContent = '📋 Copy';
                        this.style.background = '#475569';
                    }, 2000);
                } catch (e) {
                    console.error('Fallback copy failed:', e);
                }
                document.body.removeChild(textarea);
            });
        };

        urlDisplay.appendChild(urlCode);
        urlDisplay.appendChild(copyButton);
        urlSection.appendChild(urlLabel);
        urlSection.appendChild(urlDisplay);

        // Assemble the button container
        buttonContainer.appendChild(uploadButton);
        buttonContainer.appendChild(helpText);
        buttonContainer.appendChild(urlSection);

        console.log('Button container created, inserting into DOM...');

        // Insert the button above the input field
        try {
            uploadProgramContainer.insertBefore(buttonContainer, uploadProgramField);
            console.log('Button container inserted successfully');
        } catch (e) {
            console.error('Failed to insert button container:', e);
            return;
        }

        // Hide the original input field since we now have a nice button
        uploadProgramField.style.display = 'none';
        console.log('Original input field hidden');

        // Also hide the description text if it exists
        const description = uploadProgramContainer.querySelector('.field-description, .help-text, p, small');
        if (description && description.textContent.includes('Upload your Rockwell L5K file')) {
            description.style.display = 'none';
            console.log('Description text hidden');
        }

        console.log('=== PLC Simulator: Upload PLC Program link injected successfully! ===');
    }

    function injectProgramManagerLink() {
        console.log('=== injectProgramManagerLink() called ===');

        // Find the "Manage PLC Program" field
        const labels = document.querySelectorAll('label');
        console.log('Found', labels.length, 'labels on page');

        let manageLinkField = null;

        for (const label of labels) {
            const labelText = label.textContent;
            console.log('Checking label:', labelText.substring(0, 50));

            if (labelText.includes('Manage PLC Program') || labelText.includes('📁')) {
                console.log('FOUND matching label:', labelText);
                const container = label.closest('.form-group, .field-container, div');
                console.log('Container found:', !!container);

                if (container) {
                    manageLinkField = container.querySelector('input[type="text"]');
                    console.log('Input field found:', !!manageLinkField);
                    if (manageLinkField) {
                        console.log('Input field value:', manageLinkField.value);
                        break;
                    }
                }
            }
        }

        if (!manageLinkField) {
            console.log('PLC Simulator: Manage PLC Program field not found - link injection failed');
            return;
        }

        console.log('Found manageLinkField, checking if already injected...');

        // Check if link already injected
        if (manageLinkField.parentElement.querySelector('.plc-program-manager-link')) {
            console.log('Link already injected, skipping');
            return;
        }

        console.log('No existing link found, proceeding with injection...');

        // Get device name from page (usually in h1 or page title)
        let deviceName = '';
        const h1Elements = document.querySelectorAll('h1, h2, .page-title, .device-name');
        console.log('Searching for device name in', h1Elements.length, 'heading elements');

        for (const h of h1Elements) {
            const text = h.textContent.trim();
            console.log('Checking heading text:', text);
            if (text && !text.includes('Config') && !text.includes('Gateway')) {
                deviceName = text;
                console.log('Found device name from heading:', deviceName);
                break;
            }
        }

        // If couldn't find device name, try to get from URL or form
        if (!deviceName) {
            console.log('Device name not found in headings, checking URL...');
            const urlMatch = window.location.href.match(/device[=/]([^&/]+)/i);
            if (urlMatch) {
                deviceName = decodeURIComponent(urlMatch[1]);
                console.log('Found device name from URL:', deviceName);
            } else {
                console.log('Device name not found in URL either');
            }
        }

        // Construct the full URL
        const baseUrl = window.location.origin;
        console.log('Base URL:', baseUrl);

        const editProgramUrl = deviceName
            ? `${baseUrl}/res/plcsimulator/edit-program.html?device=${encodeURIComponent(deviceName)}`
            : `${baseUrl}/res/plcsimulator/edit-program.html`;

        console.log('Constructed edit program URL:', editProgramUrl);

        // Create clickable link element
        console.log('Creating link container element...');
        const linkContainer = document.createElement('div');
        linkContainer.className = 'plc-program-manager-link';
        linkContainer.style.cssText = 'margin-bottom: 12px; padding: 12px; background: #e7f3ff; border: 1px solid #0066cc; border-radius: 6px;';

        const link = document.createElement('a');
        link.href = editProgramUrl;
        link.target = '_blank';
        link.style.cssText = 'display: inline-block; padding: 10px 20px; background: #0066cc; color: white; text-decoration: none; border-radius: 4px; font-weight: 500; font-size: 14px;';
        link.innerHTML = deviceName
            ? `📁 Open Program Manager for "${deviceName}" ↗`
            : '📁 Open Program Manager ↗';

        link.addEventListener('mouseover', function() {
            this.style.background = '#0052a3';
        });
        link.addEventListener('mouseout', function() {
            this.style.background = '#0066cc';
        });

        const helpText = document.createElement('div');
        helpText.style.cssText = 'margin-top: 8px; font-size: 12px; color: #666;';
        helpText.textContent = deviceName
            ? 'Opens drag-and-drop interface for this specific device'
            : 'Opens drag-and-drop interface (device name will be detected automatically)';

        linkContainer.appendChild(link);
        linkContainer.appendChild(helpText);

        // Add full URL display with copy button
        const urlRow = document.createElement('div');
        urlRow.style.cssText = 'display: flex; align-items: center; margin-top: 10px; gap: 10px;';

        const urlLabel = document.createElement('span');
        urlLabel.style.cssText = 'font-size: 12px; color: #666; font-weight: 500; white-space: nowrap;';
        urlLabel.textContent = 'Direct URL:';

        const urlCode = document.createElement('code');
        urlCode.style.cssText = 'flex: 1; background: #2d3748; color: #68d391; padding: 6px 10px; border-radius: 4px; font-size: 12px; overflow-x: auto; white-space: nowrap;';
        urlCode.textContent = editProgramUrl;

        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.textContent = '📋 Copy';
        copyBtn.style.cssText = 'padding: 6px 12px; background: #4a5568; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px; white-space: nowrap; transition: background 0.2s;';
        copyBtn.onclick = function(e) {
            e.preventDefault();
            navigator.clipboard.writeText(editProgramUrl).then(() => {
                this.textContent = '✅ Copied!';
                this.style.background = '#48bb78';
                setTimeout(() => {
                    this.textContent = '📋 Copy';
                    this.style.background = '#4a5568';
                }, 2000);
            }).catch(err => {
                console.error('Copy failed:', err);
                // Fallback for older browsers
                const textarea = document.createElement('textarea');
                textarea.value = editProgramUrl;
                document.body.appendChild(textarea);
                textarea.select();
                document.execCommand('copy');
                document.body.removeChild(textarea);
                this.textContent = '✅ Copied!';
                this.style.background = '#48bb78';
                setTimeout(() => {
                    this.textContent = '📋 Copy';
                    this.style.background = '#4a5568';
                }, 2000);
            });
        };

        urlRow.appendChild(urlLabel);
        urlRow.appendChild(urlCode);
        urlRow.appendChild(copyBtn);
        linkContainer.appendChild(urlRow);

        console.log('Link and help text created, inserting into DOM...');
        console.log('manageLinkField.parentElement:', manageLinkField.parentElement);

        // Insert above the input field
        try {
            manageLinkField.parentElement.insertBefore(linkContainer, manageLinkField);
            console.log('Link container inserted successfully');
        } catch (e) {
            console.error('Failed to insert link container:', e);
            return;
        }

        // Make input field read-only and show full URL instead of hiding it
        manageLinkField.readOnly = true;
        manageLinkField.value = editProgramUrl;
        manageLinkField.style.cssText = 'width: 100%; color: #0066cc; background: #f0f8ff; border: 1px solid #b3d9ff; padding: 8px; border-radius: 4px; font-family: "Courier New", monospace; font-size: 13px; cursor: pointer;';
        manageLinkField.title = 'Click to select and copy URL';
        manageLinkField.onclick = function() { this.select(); };
        console.log('Original input field updated to show full URL (read-only)');

        console.log('=== PLC Simulator: Program Manager link injected successfully! ===');
    }

    function findTextareaByLabel(labelText) {
        // Search for textarea by associated label
        const labels = document.querySelectorAll('label');
        for (const label of labels) {
            if (label.textContent.includes(labelText)) {
                const container = label.closest('.form-group, .field-container, div');
                if (container) {
                    const textarea = container.querySelector('textarea');
                    if (textarea) return textarea;
                }
            }
        }

        // Fallback: search all textareas for one that looks like file content
        const textareas = document.querySelectorAll('textarea');
        for (const ta of textareas) {
            if (ta.rows > 5) { // File content textarea is likely multi-line
                return ta;
            }
        }

        return null;
    }

    function createUploadUI(textarea) {
        // Create container for upload button
        const uploadContainer = document.createElement('div');
        uploadContainer.className = 'plc-upload-container';
        uploadContainer.style.cssText = 'margin-bottom: 12px; padding: 12px; background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 4px;';

        // Create file input (hidden)
        const fileInput = document.createElement('input');
        fileInput.type = 'file';
        fileInput.accept = '.l5k,.L5K,.json,.JSON,.csv,.CSV,.xml,.XML';
        fileInput.style.display = 'none';
        fileInput.id = 'plc-file-input';

        // Create upload button
        const uploadButton = document.createElement('button');
        uploadButton.type = 'button';
        uploadButton.className = 'plc-upload-button';
        uploadButton.innerHTML = '📁 Upload PLC File';
        uploadButton.style.cssText = `
            background: #007bff;
            color: white;
            border: none;
            padding: 8px 16px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
            font-weight: 500;
            transition: background-color 0.2s;
        `;

        // Add hover effect
        uploadButton.addEventListener('mouseenter', function() {
            this.style.background = '#0056b3';
        });
        uploadButton.addEventListener('mouseleave', function() {
            this.style.background = '#007bff';
        });

        // Click button opens file dialog
        uploadButton.addEventListener('click', function() {
            fileInput.click();
        });

        // Create info text
        const infoText = document.createElement('div');
        infoText.style.cssText = 'margin-top: 8px; font-size: 13px; color: #6c757d;';
        infoText.innerHTML = `
            <strong>Upload a file</strong> or paste content below. Supported: L5K, JSON, CSV, XML
        `;

        // Handle file selection
        fileInput.addEventListener('change', function(e) {
            const file = e.target.files[0];
            if (!file) return;

            // Show loading state
            uploadButton.disabled = true;
            uploadButton.innerHTML = '⏳ Reading file...';

            // Read file
            const reader = new FileReader();
            reader.onload = function(event) {
                const content = event.target.result;

                // Populate textarea
                textarea.value = content;

                // Trigger change events so Ignition knows the value changed
                textarea.dispatchEvent(new Event('change', { bubbles: true }));
                textarea.dispatchEvent(new Event('input', { bubbles: true }));

                // Try to populate fileName field
                const fileNameInput = findInputByLabel('File Name') || findInputByLabel('FileName');
                if (fileNameInput && !fileNameInput.value) {
                    fileNameInput.value = file.name;
                    fileNameInput.dispatchEvent(new Event('change', { bubbles: true }));
                    fileNameInput.dispatchEvent(new Event('input', { bubbles: true }));
                }

                // Show success
                uploadButton.disabled = false;
                uploadButton.innerHTML = `✅ Loaded: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
                uploadButton.style.background = '#28a745';

                // Reset button after 3 seconds
                setTimeout(function() {
                    uploadButton.innerHTML = '📁 Upload PLC File';
                    uploadButton.style.background = '#007bff';
                }, 3000);

                // Clear file input so same file can be selected again
                fileInput.value = '';

                console.log('PLC Simulator: File loaded successfully -', file.name);
            };

            reader.onerror = function() {
                uploadButton.disabled = false;
                uploadButton.innerHTML = '❌ Error reading file';
                uploadButton.style.background = '#dc3545';
                alert('Error reading file: ' + reader.error);

                setTimeout(function() {
                    uploadButton.innerHTML = '📁 Upload PLC File';
                    uploadButton.style.background = '#007bff';
                }, 3000);

                console.error('PLC Simulator: File read error -', reader.error);
            };

            reader.readAsText(file);
        });

        // Assemble the upload UI
        uploadContainer.appendChild(fileInput);
        uploadContainer.appendChild(uploadButton);
        uploadContainer.appendChild(infoText);

        // Insert before the textarea
        textarea.parentElement.insertBefore(uploadContainer, textarea);
    }

    function findInputByLabel(labelText) {
        const labels = document.querySelectorAll('label');
        for (const label of labels) {
            if (label.textContent.includes(labelText)) {
                const container = label.closest('.form-group, .field-container, div');
                if (container) {
                    const input = container.querySelector('input[type="text"]');
                    if (input) return input;
                }
            }
        }
        return null;
    }

    function injectUploadPageButton() {
        console.log('=== Attempting to inject Upload Page button ===');

        // Find the "File Name" field to insert button near it
        const labels = document.querySelectorAll('label');
        let fileNameField = null;

        for (const label of labels) {
            const labelText = label.textContent.trim();
            if (labelText.includes('File Name') || labelText.includes('FileName')) {
                console.log('Found File Name label:', labelText);
                const container = label.closest('.form-group, .field-container, div');
                if (container) {
                    fileNameField = container.querySelector('input[type="text"]') ||
                                   container.querySelector('textarea');
                    if (fileNameField) {
                        console.log('Found File Name field element');
                        break;
                    }
                }
            }
        }

        if (!fileNameField) {
            console.log('File Name field not found, cannot inject button');
            return;
        }

        // Check if button already injected
        if (fileNameField.parentElement.querySelector('.plc-upload-page-button')) {
            console.log('Upload page button already injected, skipping');
            return;
        }

        console.log('Creating upload page button...');

        // Get base URL
        const baseUrl = window.location.origin;
        const uploadPageUrl = `${baseUrl}/res/plcsimulator/simple-upload.html`;

        // Create button container
        const buttonContainer = document.createElement('div');
        buttonContainer.className = 'plc-upload-page-button';
        buttonContainer.style.cssText = 'margin-bottom: 16px; padding: 16px; background: #e7f3ff; border: 2px solid #0066cc; border-radius: 8px;';

        // Create the clickable button
        const button = document.createElement('a');
        button.href = uploadPageUrl;
        button.target = '_blank';
        button.style.cssText = `
            display: inline-block;
            padding: 12px 24px;
            background: #0066cc;
            color: white;
            text-decoration: none;
            border-radius: 6px;
            font-weight: 600;
            font-size: 15px;
            transition: background-color 0.2s;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        `;
        button.innerHTML = '📤 Upload PLC File (Opens in New Tab) ↗';

        // Hover effects
        button.addEventListener('mouseover', function() {
            this.style.background = '#0052a3';
            this.style.boxShadow = '0 4px 8px rgba(0,0,0,0.15)';
        });
        button.addEventListener('mouseout', function() {
            this.style.background = '#0066cc';
            this.style.boxShadow = '0 2px 4px rgba(0,0,0,0.1)';
        });

        // Help text
        const helpText = document.createElement('div');
        helpText.style.cssText = 'margin-top: 10px; font-size: 13px; color: #555; line-height: 1.5;';
        helpText.innerHTML = `
            <strong>Click the button above</strong> to open the file upload page.<br>
            Upload your L5K, JSON, or CSV file there, then copy the content and paste it into the "File Content" field below.
        `;

        buttonContainer.appendChild(button);
        buttonContainer.appendChild(helpText);

        console.log('Inserting button into DOM...');

        // Insert at the very top of the parent container
        try {
            const formContainer = fileNameField.closest('.form-container, form, .settings-panel') ||
                                 fileNameField.parentElement.parentElement;

            if (formContainer) {
                formContainer.insertBefore(buttonContainer, formContainer.firstChild);
                console.log('Upload page button inserted at top of form successfully');
            } else {
                // Fallback: insert above the file name field
                fileNameField.parentElement.insertBefore(buttonContainer, fileNameField.parentElement.firstChild);
                console.log('Upload page button inserted above file name field');
            }
        } catch (e) {
            console.error('Failed to insert upload page button:', e);
            return;
        }

        console.log('=== Upload Page button injected successfully! ===');
    }

    console.log('PLC Simulator: File upload enhancement loaded successfully');
})();
