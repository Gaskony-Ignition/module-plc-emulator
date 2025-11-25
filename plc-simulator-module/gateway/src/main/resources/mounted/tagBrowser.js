/**
 * Enhanced PLC Simulator - Tag Browser
 *
 * This module provides the Tag Browser page embedded in the Gateway web interface.
 * Uses an iframe to display the authenticated tag browser page within the Gateway frame.
 */

(function() {
    'use strict';

    console.log('PLC Simulator Tag Browser: Loading...');

    // Only run on the correct page
    if (!window.location.pathname.includes('/plc-tag-browser')) {
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
        if (document.getElementById('plc-tagbrowser-iframe')) {
            return;
        }

        // Create iframe to embed our page
        var iframe = document.createElement('iframe');
        iframe.id = 'plc-tagbrowser-iframe';
        iframe.src = '/data/plcsimulator/tag-browser';
        iframe.style.cssText = 'width: 100%; height: calc(100vh - 120px); border: none; min-height: 600px;';
        iframe.title = 'PLC Tag Browser';

        // Clear the container and add iframe
        if (container !== document.body) {
            container.innerHTML = '';
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

        console.log('PLC Simulator Tag Browser: Page embedded');
    }

    // Try to embed immediately or wait for DOM
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', embedPage);
    } else {
        // Small delay to let Gateway framework render
        setTimeout(embedPage, 100);
    }
})();
