/**
 * Enhanced PLC Simulator - Tag Browser
 *
 * This module provides the Tag Browser page embedded in the Gateway web interface.
 * Uses an iframe to display the authenticated tag browser page within the Gateway frame.
 */

(function() {
    'use strict';

    // Only run on the correct page
    if (!window.location.pathname.includes('/plc-tag-browser')) {
        return;
    }

    console.log('PLC Simulator Tag Browser: Loading...');

    // Find the main content area and embed our page in an iframe
    function embedPage() {
        // Check if we already embedded
        if (document.getElementById('plc-tagbrowser-iframe')) {
            return;
        }

        // Find the Gateway's main content container - be specific to avoid matching wrong elements
        var container = document.querySelector('.main-content') ||
                        document.querySelector('#main-content') ||
                        document.querySelector('.gateway-page-content') ||
                        document.querySelector('[data-component="page-content"]');

        // Create iframe to embed our page
        var iframe = document.createElement('iframe');
        iframe.id = 'plc-tagbrowser-iframe';
        iframe.src = '/data/plcsimulator/tag-browser';
        iframe.title = 'PLC Tag Browser';

        if (container) {
            // Clear container and add iframe
            container.innerHTML = '';
            iframe.style.cssText = 'width: 100%; height: calc(100vh - 120px); border: none; min-height: 600px; background: #0f172a;';
            container.appendChild(iframe);
        } else {
            // Fallback: use fixed positioning over the page
            iframe.style.cssText = 'position: fixed; top: 60px; left: 0; right: 0; bottom: 0; width: 100%; height: calc(100vh - 60px); border: none; z-index: 1000; background: #0f172a;';
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
