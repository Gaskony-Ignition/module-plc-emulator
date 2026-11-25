/** Returns a CSS variable reference for a device/resource status string. */
export function getStatusColor(status: string): string {
  const s = status?.toLowerCase() ?? '';
  // Errors first: 'disconnected' contains 'connected'.
  if (s.includes('error') || s.includes('fault') || s.includes('fail') || s.includes('disconnected')) {
    return 'var(--error)';
  }
  if (s.includes('connected') || s.includes('running') || s.includes('ok') || s.includes('enabled')) {
    return 'var(--success)';
  }
  if (s.includes('warn') || s.includes('loading') || s.includes('pending')) {
    return 'var(--warning)';
  }
  return 'var(--text-muted)';
}
