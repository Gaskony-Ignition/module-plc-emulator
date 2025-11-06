"""
Configuration loader for PLC OPC-UA Simulator.
"""

import yaml
from pathlib import Path
from typing import Dict, Any, List, Optional
import logging

logger = logging.getLogger(__name__)


class SimulatorConfig:
    """Configuration manager for the OPC-UA simulator"""

    def __init__(self, config_file: str = "config.yaml"):
        self.config_file = Path(config_file)
        self.config = self._load_config()

    def _load_config(self) -> Dict[str, Any]:
        """Load configuration from YAML file"""
        if not self.config_file.exists():
            logger.warning(f"Config file not found: {self.config_file}")
            logger.info("Using default configuration")
            return self._get_default_config()

        try:
            with open(self.config_file, 'r') as f:
                config = yaml.safe_load(f)
                logger.info(f"Loaded configuration from: {self.config_file}")
                return config
        except Exception as e:
            logger.error(f"Error loading config file: {e}")
            logger.info("Using default configuration")
            return self._get_default_config()

    def _get_default_config(self) -> Dict[str, Any]:
        """Return default configuration"""
        return {
            'server': {
                'endpoint': 'opc.tcp://0.0.0.0:4840/plc-simulator/',
                'name': 'PLC Simulator OPC-UA Server',
                'namespace': 'http://plc-simulator.opcua',
                'hot_reload': False,
                'reload_interval': 5
            },
            'plcs': [
                {
                    'name': 'DemoWWTP',
                    'enabled': True,
                    'file': '../DemoWWTP-sample-a.L5K',
                    'parser': 'rockwell',
                    'parser_options': {
                        'select_tags': True,
                        'allow_hidden': False,
                        'use_stored_values': True
                    },
                    'simulation': {
                        'enabled': False,
                        'update_interval': 1.0,
                        'rules': {},
                        'defaults': {
                            'BOOL': 'static',
                            'INT': 'static',
                            'REAL': 'static',
                            'STRING': 'static'
                        }
                    }
                }
            ],
            'logging': {
                'level': 'INFO',
                'format': '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
                'file': {
                    'enabled': False,
                    'path': 'opcua_simulator.log',
                    'max_bytes': 10485760,
                    'backup_count': 5
                }
            },
            'security': {
                'enabled': False
            }
        }

    def get_server_config(self) -> Dict[str, Any]:
        """Get server configuration"""
        return self.config.get('server', {})

    def get_plcs(self) -> List[Dict[str, Any]]:
        """Get list of PLC configurations (only enabled ones)"""
        plcs = self.config.get('plcs', [])
        return [plc for plc in plcs if plc.get('enabled', True)]

    def get_logging_config(self) -> Dict[str, Any]:
        """Get logging configuration"""
        return self.config.get('logging', {})

    def reload(self):
        """Reload configuration from file"""
        self.config = self._load_config()
        logger.info("Configuration reloaded")
