/**
 * Enhanced PLC Simulator - File Upload
 *
 * This module provides the File Upload page embedded in the Gateway web interface.
 * Renders an iframe within the Gateway's page content area.
 */

(function() {
    'use strict';

    // Only run on the correct page
    if (!window.location.pathname.includes('/plc-file-upload')) {
        return;
    }

    console.log('PLC Simulator File Upload: Initializing...');

    function embedPage() {
        // Prevent duplicate embedding
        if (document.getElementById('plc-upload-iframe')) {
            console.log('PLC Simulator File Upload: Already embedded');
            return;
        }

        // Create iframe
        var iframe = document.createElement('iframe');
        iframe.id = 'plc-upload-iframe';
        iframe.src = '/data/plcsimulator/page';
        iframe.title = 'PLC File Upload';
        iframe.style.cssText = 'width: 100%; height: calc(100vh - 150px); border: none; min-height: 500px; background: #0f172a; display: block;';

        // Find where to insert - look for the main content wrapper
        // The Gateway uses React, so we need to find the right container
        var attempts = 0;
        var maxAttempts = 20;

        function tryInsert() {
            attempts++;

            // Look for common Gateway content containers
            var container = document.querySelector('[class*="page-content"]') ||
                           document.querySelector('[class*="PageContent"]') ||
                           document.querySelector('.main-content') ||
                           document.querySelector('#main-content') ||
                           document.querySelector('[role="main"]') ||
                           document.querySelector('main');

            if (container && !document.getElementById('plc-upload-iframe')) {
                // Don't clear - just append
                container.appendChild(iframe);
                console.log('PLC Simulator File Upload: Embedded in container');
                return true;
            }

            // Fallback: append to body with positioning that doesn't cover menu
            if (attempts >= maxAttempts) {
                if (!document.getElementById('plc-upload-iframe')) {
                    // Create a wrapper div positioned below the header
                    var wrapper = document.createElement('div');
                    wrapper.id = 'plc-upload-wrapper';
                    wrapper.style.cssText = 'position: absolute; top: 120px; left: 250px; right: 0; bottom: 0; overflow: hidden;';
                    wrapper.appendChild(iframe);
                    iframe.style.height = '100%';
                    document.body.appendChild(wrapper);
                    console.log('PLC Simulator File Upload: Embedded with fallback positioning');
                }
                return true;
            }

            // Retry
            setTimeout(tryInsert, 100);
            return false;
        }

        tryInsert();
    }

    // Wait for DOM then embed
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            setTimeout(embedPage, 200);
        });
    } else {
        setTimeout(embedPage, 200);
    }
})();
