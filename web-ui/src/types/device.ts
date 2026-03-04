/** Shared device-related TypeScript interfaces used across multiple views. */

export interface DeviceInfo {
  name: string;
  status: string;
  enabled: boolean;
  fileName: string;
  parserType: string;
  simulationEnabled: boolean;
}

export interface DeviceStatus {
  success: boolean;
  deviceName: string;
  status: string;
  fileName: string;
  hasFile: boolean;
  fileSize: number;
  lastModified: number;
  filePath: string | null;
  parserType: string;
  enabled: boolean;
  simulationEnabled: boolean;
}

export interface TagStats {
  success: boolean;
  totalTags: number;
  folders: number;
  udtInstances: number;
}
