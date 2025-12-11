/**
 * Enhanced PLC Simulator - File Upload Enhancement
 *
 * This script adds a file upload button to the device configuration form.
 * Auto-loaded when viewing Enhanced PLC Simulator device config pages.
 *
 * Note: The main upload interface is now embedded via iframes in the gateway UI.
 * This script provides an additional local file picker directly in the config form.
 */

(function() {
    'use strict';

    console.log('PLC Simulator: File upload enhancement loading...');

    // Prevent multiple injections
    if (window.plcSimulatorUploadInjected) {
        console.log('PLC Simulator: Upload UI already injected');
        return;
    }
    window.plcSimulatorUploadInjected = true;

    // Wait for DOM to be ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initFileUpload);
    } else {
        initFileUpload();
    }

    function initFileUpload() {
        // Find the textarea for file content
        const textarea = findTextareaByLabel('PLC File Content') || findTextareaByLabel('File Content (Internal)');

        if (!textarea) {
            console.log('PLC Simulator: File content textarea not found, retrying in 1s...');
            setTimeout(initFileUpload, 1000);
            return;
        }

        // Check if we already added the upload button
        if (textarea.parentElement.querySelector('.plc-upload-button')) {
            console.log('PLC Simulator: Upload button already exists');
            return;
        }

        // Create file upload UI
        createUploadUI(textarea);
        console.log('PLC Simulator: File upload UI injected successfully');
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
        uploadButton.textContent = 'Upload PLC File';
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
        infoText.innerHTML = '<strong>Upload a file</strong> or paste content below. Supported: L5K, JSON, CSV, XML';

        // Handle file selection
        fileInput.addEventListener('change', function(e) {
            const file = e.target.files[0];
            if (!file) return;

            // Show loading state
            uploadButton.disabled = true;
            uploadButton.textContent = 'Reading file...';

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
                uploadButton.textContent = 'Loaded: ' + file.name + ' (' + (file.size / 1024).toFixed(1) + ' KB)';
                uploadButton.style.background = '#28a745';

                // Reset button after 3 seconds
                setTimeout(function() {
                    uploadButton.textContent = 'Upload PLC File';
                    uploadButton.style.background = '#007bff';
                }, 3000);

                // Clear file input so same file can be selected again
                fileInput.value = '';

                console.log('PLC Simulator: File loaded successfully -', file.name);
            };

            reader.onerror = function() {
                uploadButton.disabled = false;
                uploadButton.textContent = 'Error reading file';
                uploadButton.style.background = '#dc3545';
                alert('Error reading file: ' + reader.error);

                setTimeout(function() {
                    uploadButton.textContent = 'Upload PLC File';
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
