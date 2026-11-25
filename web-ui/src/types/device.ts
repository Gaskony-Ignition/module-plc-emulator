/** Shared device-related TypeScript interfaces used across multiple views. */

export interface DeviceInfo {
  name: string;
  status: string;
  enabled: boolean;
  fileName: string;
  parserType: string;
  simulationEnabled: boolean;
  /** Simulation on in config AND the program file loaded, so the engine is running (issue #12). */
  simulating: boolean;
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
  simulating: boolean;
}

export interface TagStats {
  success: boolean;
  totalTags: number;
  folders: number;
  udtInstances: number;
}

/** A single retained file version for a device (defect B5). */
export interface FileVersionInfo {
  filename: string;
  size: number;
  timestamp: number;
  current: boolean;
}

export interface VersionsResponse {
  success: boolean;
  deviceName: string;
  versions: FileVersionInfo[];
  count: number;
  maxVersions: number;
}
