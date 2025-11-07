import React from 'react';
import ReactDOM from 'react-dom';
import { DeviceManager } from './DeviceManager';
import { FileUploadDialog } from './FileUploadDialog';

// Export components for use in Ignition Gateway
export { DeviceManager, FileUploadDialog };

// Mount function for Ignition's BasicReactPanel
export function mount(container: HTMLElement, props: any) {
  ReactDOM.render(<DeviceManager {...props} />, container);
}

// Unmount function
export function unmount(container: HTMLElement) {
  ReactDOM.unmountComponentAtNode(container);
}

// Make available globally for Ignition
if (typeof window !== 'undefined') {
  (window as any).PLCSimulator = {
    DeviceManager,
    FileUploadDialog,
    mount,
    unmount,
  };
}
