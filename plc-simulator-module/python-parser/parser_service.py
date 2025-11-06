#!/usr/bin/env python3
"""
PLC Parser REST API Service
Provides HTTP endpoints for parsing PLC files (L5K, JSON, etc.)
Returns structured JSON that can be consumed by the Ignition module.
"""

import sys
import os
import json
import logging
from flask import Flask, request, jsonify
from pathlib import Path
import tempfile

# Add parent directory to path to import parsers
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'plc-simulator-refactored'))

from parsers.rockwell_l5k_parser import RockwellL5KParser
from parsers.json_parser import JSONParser
from models.tag_model import PLCProject

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Initialize parsers
l5k_parser = RockwellL5KParser()
json_parser = JSONParser()


@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    return jsonify({
        'status': 'ok',
        'service': 'PLC Parser Service',
        'version': '1.0.0'
    })


@app.route('/parse/l5k', methods=['POST'])
def parse_l5k():
    """
    Parse Rockwell L5K file content.

    Request body: Raw L5K file content (text)
    Returns: JSON structure with tags, programs, UDTs
    """
    try:
        # Get L5K content from request
        l5k_content = request.data.decode('utf-8')

        if not l5k_content:
            return jsonify({'error': 'No L5K content provided'}), 400

        logger.info(f"Received L5K content ({len(l5k_content)} bytes)")

        # Write to temporary file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.L5K', delete=False) as tmp_file:
            tmp_file.write(l5k_content)
            tmp_file_path = tmp_file.name

        try:
            # Parse the L5K file
            logger.info(f"Parsing L5K file: {tmp_file_path}")
            project = l5k_parser.parse(tmp_file_path)

            # Convert to JSON-serializable format
            result = project_to_dict(project)

            logger.info(f"Parse successful: {result['name']} with {result['tag_count']} tags")

            return jsonify(result)

        finally:
            # Clean up temp file
            try:
                os.unlink(tmp_file_path)
            except:
                pass

    except Exception as e:
        logger.error(f"Error parsing L5K: {e}", exc_info=True)
        return jsonify({'error': str(e)}), 500


@app.route('/parse/json', methods=['POST'])
def parse_json():
    """
    Parse JSON PLC configuration file.

    Request body: Raw JSON content
    Returns: JSON structure with tags, programs, UDTs
    """
    try:
        # Get JSON content from request
        json_content = request.data.decode('utf-8')

        if not json_content:
            return jsonify({'error': 'No JSON content provided'}), 400

        logger.info(f"Received JSON content ({len(json_content)} bytes)")

        # Write to temporary file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as tmp_file:
            tmp_file.write(json_content)
            tmp_file_path = tmp_file.name

        try:
            # Parse the JSON file
            logger.info(f"Parsing JSON file: {tmp_file_path}")
            project = json_parser.parse(tmp_file_path)

            # Convert to JSON-serializable format
            result = project_to_dict(project)

            logger.info(f"Parse successful: {result['name']} with {result['tag_count']} tags")

            return jsonify(result)

        finally:
            # Clean up temp file
            try:
                os.unlink(tmp_file_path)
            except:
                pass

    except Exception as e:
        logger.error(f"Error parsing JSON: {e}", exc_info=True)
        return jsonify({'error': str(e)}), 500


def project_to_dict(project: PLCProject) -> dict:
    """
    Convert PLCProject to JSON-serializable dictionary.

    Args:
        project: PLCProject instance

    Returns:
        Dictionary with project structure
    """
    # Collect all tags from all programs
    all_tags = []

    # Add controller-scoped tags
    controller_tags = []
    for tag in project.global_tags:
        controller_tags.append({
            'name': tag.name,
            'data_type': tag.data_type,
            'scope': 'Controller:Global',
            'initial_value': tag.initial_value
        })

    # Add program-scoped tags
    program_tags = {}
    for program in project.programs:
        prog_tags = []
        for tag in program.tags:
            prog_tags.append({
                'name': tag.name,
                'data_type': tag.data_type,
                'scope': f'Program:{program.name}',
                'initial_value': tag.initial_value
            })
        program_tags[program.name] = prog_tags
        all_tags.extend(prog_tags)

    all_tags.extend(controller_tags)

    # Build UDT information
    udts = []
    for udt in project.udts:
        members = []
        for member in udt.members:
            members.append({
                'name': member.name,
                'data_type': member.data_type,
                'description': getattr(member, 'description', '')
            })

        udts.append({
            'name': udt.name,
            'members': members
        })

    return {
        'name': project.name,
        'controller_tags': controller_tags,
        'programs': program_tags,
        'udts': udts,
        'tag_count': len(all_tags)
    }


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='PLC Parser REST API Service')
    parser.add_argument('--host', default='127.0.0.1', help='Host to bind to (default: 127.0.0.1)')
    parser.add_argument('--port', type=int, default=5000, help='Port to bind to (default: 5000)')
    parser.add_argument('--debug', action='store_true', help='Enable debug mode')

    args = parser.parse_args()

    logger.info(f"Starting PLC Parser Service on {args.host}:{args.port}")
    app.run(host=args.host, port=args.port, debug=args.debug)
