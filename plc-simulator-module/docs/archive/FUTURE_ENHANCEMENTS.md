# Future Enhancements for Enhanced PLC Simulator

## File Upload Feature (Like Programmable Device Simulator)

### Current Limitation

The current implementation uses `FormFieldType.TEXTAREA` for file content input, requiring users to:
1. Open their PLC file (L5K, JSON, etc.) in a text editor
2. Select all content (Ctrl+A)
3. Copy to clipboard (Ctrl+C)
4. Paste into the textarea field in the Gateway config

This works but is less user-friendly than a drag-and-drop file upload.

### Desired UX (Based on Programmable Device Simulator)

The built-in Programmable Device Simulator has a much better UX:
1. Create device connection (simple form)
2. After creation, click 3-dots menu → "Edit Program"
3. Opens custom dialog with:
   - "Import" button
   - Drag-and-drop file upload zone
   - "Accepts CSV files" message
   - File preview/validation

### Why This Isn't Implemented Yet

**FormFieldType.FILE doesn't exist or doesn't work as expected** in the current Ignition SDK. After researching official examples and documentation:

1. **No file upload field type** - FormFieldType enum only has: TEXT, TEXTAREA, NUMBER, SELECT, CHECKBOX, SECRET
2. **3-dots menu not accessible** - No public SDK API to add items to device context menu
3. **Requires custom React UI** - Would need to build full-stack solution:
   - Java backend: RouteGroup for REST endpoints, BasicReactPanel for UI mounting
   - React frontend: TypeScript/React components, webpack build
   - File handling: Use base64 encoding (multipart uploads have known issues until Ignition 8.3)

### Implementation Roadmap

#### Phase 1: Keep Current Approach (DONE ✅)
- TEXTAREA for copy/paste workflow
- Save content to Gateway filesystem automatically
- Works reliably with all file sizes

#### Phase 2: Add Basic File Path Input (Future)
- Add text field for existing file path on Gateway filesystem
- Let users manually copy files to `/usr/local/bin/ignition/data/plc-simulator/`
- Read from path if provided, fall back to pasted content

#### Phase 3: Custom React UI with Drag-and-Drop (Future)
Required components:
1. **Backend (Java)**:
   ```java
   // In ModuleHook
   @Override
   protected void mountRouteHandlers(RouteGroup routes) {
       routes.newRoute("/device/upload")
           .handler(this::handleFileUpload)
           .mount();
   }

   private void handleFileUpload(RoutingContext ctx) {
       // Parse base64-encoded file from JSON body
       // Validate file type and content
       // Save to filesystem
       // Return success/error response
   }
   ```

2. **Frontend (React/TypeScript)**:
   ```typescript
   // FileUploadDialog.tsx
   import { useDropzone } from 'react-dropzone';

   const FileUploadDialog = () => {
       const onDrop = async (files: File[]) => {
           const file = files[0];
           const base64 = await fileToBase64(file);

           await fetch('/system/plcsimulator/device/upload', {
               method: 'POST',
               body: JSON.stringify({
                   fileName: file.name,
                   fileContent: base64
               })
           });
       };

       const {getRootProps, getInputProps} = useDropzone({
           onDrop,
           accept: {'.l5k': [], '.json': [], '.csv': []}
       });

       return (
           <div {...getRootProps()} className="dropzone">
               <input {...getInputProps()} />
               <p>Drag a file here or click to upload</p>
               <p className="file-types">Accepts L5K, JSON, CSV files</p>
           </div>
       );
   };
   ```

3. **Build System**:
   - Add webpack configuration for TypeScript/React
   - Bundle to `gateway/src/main/resources/mounted/`
   - Register in ModuleHook

#### Phase 4: Advanced Features (Future)
- File validation and preview before import
- Support for multiple file formats
- CSV column mapping UI
- Import history and rollback

### Resources

**Official Documentation:**
- SDK Docs: https://www.sdk-docs.inductiveautomation.com/docs/opc-ua-driver-development/
- OPC UA Device Example: https://github.com/inductiveautomation/ignition-sdk-examples/tree/master/opc-ua-device
- Gateway Webpage Example: https://github.com/inductiveautomation/ignition-sdk-examples/tree/master/gateway-webpage

**Known Issues:**
- Forum discussion on multipart uploads: Issue #100909
- Expected fix in Ignition 8.3 with Jetty 12 upgrade
- Workaround: Use base64-encoded JSON instead of multipart/form-data

### Recommendation

**For v1.x releases:** Keep the TEXTAREA copy/paste approach. It's simple, reliable, and works for all users.

**For v2.0:** Consider implementing the full React UI with drag-and-drop once:
1. Ignition 8.3 is released with improved file upload support
2. Team has React/TypeScript developers available
3. There's user demand for the improved UX

The current TEXTAREA approach is not ideal but is a reasonable MVP that allows users to import PLC files without custom UI development.
