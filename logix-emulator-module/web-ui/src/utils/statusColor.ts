/** Returns a CSS colour hex for a device/resource status string. */
export function getStatusColor(status: string): string {
  const s = status?.toLowerCase() ?? '';
  if (s.includes('connected') || s.includes('running') || s.includes('ok') || s.includes('enabled')) {
    return '#98c379'; // --accent-success
  }
  if (s.includes('error') || s.includes('fault') || s.includes('fail') || s.includes('disconnected')) {
    return '#e06c75'; // --accent-error
  }
  if (s.includes('warn') || s.includes('loading') || s.includes('pending')) {
    return '#e5c07b'; // --accent-warning
  }
  return '#6b7280'; // --text-muted
}
