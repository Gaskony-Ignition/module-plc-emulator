"""
Simulation engine for generating dynamic tag values.
"""

import time
import math
import random
import logging
from typing import Any, Dict, Optional
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class SimulationState:
    """State for a simulated value"""
    current_value: Any
    start_time: float
    last_update: float
    cycle_count: int = 0


class SimulationEngine:
    """
    Engine for simulating dynamic tag values.
    Supports various simulation types: ramp, sine, toggle, random, noise, etc.
    """

    def __init__(self):
        self.states: Dict[str, SimulationState] = {}
        self.start_time = time.time()

    def initialize_tag(self, tag_path: str, initial_value: Any):
        """Initialize simulation state for a tag"""
        if tag_path not in self.states:
            self.states[tag_path] = SimulationState(
                current_value=initial_value,
                start_time=self.start_time,
                last_update=self.start_time
            )

    def get_simulated_value(
        self,
        tag_path: str,
        sim_config: Dict[str, Any],
        data_type: str
    ) -> Any:
        """
        Get the next simulated value for a tag.

        Args:
            tag_path: Full path to the tag
            sim_config: Simulation configuration for this tag
            data_type: Data type of the tag (BOOL, INT, REAL, etc.)

        Returns:
            Simulated value
        """
        if tag_path not in self.states:
            logger.warning(f"Tag '{tag_path}' not initialized for simulation")
            return self._get_default_value(data_type)

        state = self.states[tag_path]
        sim_type = sim_config.get('type', 'static')

        now = time.time()
        elapsed = now - state.start_time
        delta_t = now - state.last_update

        # Update state
        state.last_update = now

        # Apply simulation based on type
        if sim_type == 'static':
            return state.current_value

        elif sim_type == 'ramp':
            return self._simulate_ramp(state, sim_config, elapsed)

        elif sim_type == 'sine':
            return self._simulate_sine(state, sim_config, elapsed)

        elif sim_type == 'toggle':
            return self._simulate_toggle(state, sim_config, elapsed)

        elif sim_type == 'random_bool':
            return self._simulate_random_bool(state, sim_config)

        elif sim_type == 'random':
            return self._simulate_random(state, sim_config, data_type)

        elif sim_type == 'noise':
            return self._simulate_noise(state, sim_config, data_type)

        elif sim_type == 'pulse':
            return self._simulate_pulse(state, sim_config, elapsed)

        else:
            logger.warning(f"Unknown simulation type: {sim_type}")
            return state.current_value

    def _simulate_ramp(self, state: SimulationState, config: Dict, elapsed: float) -> float:
        """Simulate a ramp (linear increase/decrease)"""
        min_val = config.get('min', 0)
        max_val = config.get('max', 100)
        step = config.get('step', 1.0)
        reverse = config.get('reverse', False)

        # Calculate new value
        if reverse:
            new_val = state.current_value - step
            if new_val <= min_val:
                new_val = max_val
        else:
            new_val = state.current_value + step
            if new_val >= max_val:
                new_val = min_val

        state.current_value = new_val
        return new_val

    def _simulate_sine(self, state: SimulationState, config: Dict, elapsed: float) -> float:
        """Simulate a sine wave"""
        min_val = config.get('min', 0)
        max_val = config.get('max', 100)
        period = config.get('period', 60)  # seconds
        phase = config.get('phase', 0)  # radians

        amplitude = (max_val - min_val) / 2
        offset = min_val + amplitude

        value = offset + amplitude * math.sin(2 * math.pi * elapsed / period + phase)
        state.current_value = value
        return value

    def _simulate_toggle(self, state: SimulationState, config: Dict, elapsed: float) -> bool:
        """Simulate a boolean toggle at regular intervals"""
        interval = config.get('interval', 10)  # seconds

        # Toggle every interval seconds
        cycle = int(elapsed / interval)
        if cycle != state.cycle_count:
            state.current_value = not state.current_value
            state.cycle_count = cycle

        return state.current_value

    def _simulate_random_bool(self, state: SimulationState, config: Dict) -> bool:
        """Simulate a random boolean with given probability"""
        probability = config.get('probability', 0.5)
        value = random.random() < probability
        state.current_value = value
        return value

    def _simulate_random(self, state: SimulationState, config: Dict, data_type: str) -> Any:
        """Simulate random values within a range"""
        min_val = config.get('min', 0)
        max_val = config.get('max', 100)

        if data_type in ['INT', 'INT1', 'INT2', 'INT4']:
            value = random.randint(int(min_val), int(max_val))
        else:  # REAL/FLOAT
            value = random.uniform(min_val, max_val)

        state.current_value = value
        return value

    def _simulate_noise(self, state: SimulationState, config: Dict, data_type: str) -> Any:
        """Add random noise to current value"""
        amplitude = config.get('amplitude', 1.0)

        # Add gaussian noise
        noise = random.gauss(0, amplitude)
        value = state.current_value + noise

        # Clamp to limits if provided
        min_val = config.get('min', None)
        max_val = config.get('max', None)

        if min_val is not None and value < min_val:
            value = min_val
        if max_val is not None and value > max_val:
            value = max_val

        state.current_value = value
        return value

    def _simulate_pulse(self, state: SimulationState, config: Dict, elapsed: float) -> bool:
        """Simulate a pulse wave (square wave)"""
        period = config.get('period', 10)  # seconds
        duty_cycle = config.get('duty_cycle', 0.5)  # 0 to 1

        # Calculate position in cycle
        cycle_pos = (elapsed % period) / period

        value = cycle_pos < duty_cycle
        state.current_value = value
        return value

    def _get_default_value(self, data_type: str) -> Any:
        """Get default value for a data type"""
        defaults = {
            'BOOL': False,
            'INT': 0,
            'INT1': 0,
            'INT2': 0,
            'INT4': 0,
            'REAL': 0.0,
            'FLOAT4': 0.0,
            'STRING': '',
        }
        return defaults.get(data_type, 0)
