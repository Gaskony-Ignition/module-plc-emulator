/**
 * Enhanced PLC Simulator - File Upload
 *
 * This module provides the File Upload page embedded in the Gateway web interface.
 * Uses an iframe to display the authenticated file upload page within the Gateway frame.
 */

(function() {
    'use strict';

    console.log('PLC Simulator File Upload: Loading...');

    // Only run on the correct page
    if (!window.location.pathname.includes('/plc-file-upload')) {
        return;
    }

    // Find the main content area and embed our page in an iframe
    function embedPage() {
        // Wait for the page to be ready
        var container = document.querySelector('.main-content') ||
                        document.querySelector('#main-content') ||
                        document.querySelector('[class*="content"]') ||
                        document.body;

        // Check if we already embedded
        if (document.getElementById('plc-upload-iframe')) {
            return;
        }

        // Create iframe to embed our page
        var iframe = document.createElement('iframe');
        iframe.id = 'plc-upload-iframe';
        iframe.src = '/data/plcsimulator/page';
        iframe.style.cssText = 'width: 100%; height: calc(100vh - 120px); border: none; min-height: 600px;';
        iframe.title = 'PLC File Upload';

        // Add iframe to container without clearing (to preserve Gateway menu)
        if (container !== document.body) {
            // Don't clear innerHTML to preserve existing Gateway navigation
            container.appendChild(iframe);
        } else {
            // Fallback: append to body
            iframe.style.position = 'fixed';
            iframe.style.top = '50px';
            iframe.style.left = '0';
            iframe.style.right = '0';
            iframe.style.bottom = '0';
            iframe.style.height = 'calc(100vh - 50px)';
            iframe.style.zIndex = '1000';
            iframe.style.backgroundColor = '#0f172a';
            document.body.appendChild(iframe);
        }

        console.log('PLC Simulator File Upload: Page embedded');
    }

    // Try to embed immediately or wait for DOM
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', embedPage);
    } else {
        // Small delay to let Gateway framework render
        setTimeout(embedPage, 100);
    }
})();
