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

        // First, try to inject the clickable Program Manager link
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

        // Hide the input field since we have the link now
        manageLinkField.style.display = 'none';
        console.log('Original input field hidden');

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

    console.log('PLC Simulator: File upload enhancement loaded successfully');
})();
