/** Returns a CSS colour hex for a device/resource status string. */
export function getStatusColor(status: string): string {
  const s = status?.toLowerCase() ?? '';
  if (s.includes('connected') || s.includes('running') || s.includes('ok') || s.includes('enabled')) {
    return '#a6e3a1'; // --accent-success
  }
  if (s.includes('error') || s.includes('fault') || s.includes('fail') || s.includes('disconnected')) {
    return '#f38ba8'; // --accent-error
  }
  if (s.includes('warn') || s.includes('loading') || s.includes('pending')) {
    return '#f9e2af'; // --accent-warning
  }
  return '#6c7086'; // --text-muted
}
