#!/bin/bash
# config_and_db_upgrade.sh
# Script to compare config property files with deprecated, modified, and added configs for version upgrade.
# Usage: ./config_and_db_upgrade.sh <local_config.properties> <from_version> <to_version>

# Only enable set -e after upgrade/rollback handling to allow error capture

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <local_config.properties> <from_version> <to_version>"
  exit 1
fi

LOCAL_CONFIG="$1"
FROM_VERSION="$2"
TO_VERSION="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_DIR="$SCRIPT_DIR/${FROM_VERSION}_to_${TO_VERSION}"

DEPRECATED_FILE="$VERSION_DIR/deprecated.properties"
MODIFIED_FILE="$VERSION_DIR/modified.properties"

LOG_FILE="$SCRIPT_DIR/config_and_db_upgrade.log"
# Overwrite log file at the start of every run
: > "$LOG_FILE"
log_to_file() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2" >> "$LOG_FILE"
}

if [ ! -f "$LOCAL_CONFIG" ]; then
  echo "Local config file not found: $LOCAL_CONFIG"
  log_to_file "ERROR" "Local config file not found: $LOCAL_CONFIG"
  exit 1
fi
if [ ! -d "$VERSION_DIR" ]; then
  echo "Version directory not found: $VERSION_DIR"
  log_to_file "ERROR" "Version directory not found: $VERSION_DIR"
  exit 1
fi

# Read plugin mode from local config
PLUGIN_MODE=$(grep '^mosip.certify.plugin-mode=' "$LOCAL_CONFIG" | cut -d'=' -f2 | tr -d '[:space:]')
if [ -z "$PLUGIN_MODE" ]; then
  echo "ERROR: mosip.certify.plugin-mode not set in $LOCAL_CONFIG. Please set it to either DataProvider or VCIssuance."
  log_to_file "ERROR" "mosip.certify.plugin-mode not set in $LOCAL_CONFIG. Please set it to either DataProvider or VCIssuance."
  exit 1
fi

ADDED_FILE="$VERSION_DIR/added.${PLUGIN_MODE}.properties"
if [ ! -f "$ADDED_FILE" ]; then
  echo "ERROR: Added properties file for plugin mode '$PLUGIN_MODE' not found: $ADDED_FILE"
  log_to_file "ERROR" "Added properties file for plugin mode '$PLUGIN_MODE' not found: $ADDED_FILE"
  exit 1
fi

missing_flag=0
warning_flag=0

# Check deprecated properties
if [ -f "$DEPRECATED_FILE" ]; then
  while IFS= read -r line; do
    prop=$(echo "$line" | sed 's/[[:space:]]*=[[:space:]]*.*//')
    if grep -q "^$prop=" "$LOCAL_CONFIG"; then
      echo "WARNING: Deprecated property found in local config: $prop"
      log_to_file "WARNING" "Deprecated property found in local config: $prop"
      warning_flag=1
    fi
  done < "$DEPRECATED_FILE"
fi

# Check modified properties
if [ -f "$MODIFIED_FILE" ]; then
  while IFS= read -r line; do
    prop=$(echo "$line" | sed 's/[[:space:]]*=[[:space:]]*.*//')
    if grep -q "^$prop=" "$LOCAL_CONFIG"; then
      echo "WARNING: Modified property identified in local config: $prop. Please verify it with inji-config repository before proceeding."
      log_to_file "WARNING" "Modified property identified in local config: $prop. Please verify it with inji-config repository before proceeding."
      warning_flag=1
    fi
  done < "$MODIFIED_FILE"
fi

# Check added properties (required and optional)
if [ -f "$ADDED_FILE" ]; then
  while IFS= read -r line; do
    # Skip empty lines and comments
    [ -z "$line" ] && continue
    [[ "$line" =~ ^# ]] && continue
    if [[ "$line" == required:* ]]; then
      prop=$(echo "$line" | sed 's/^required:[[:space:]]*//' | sed 's/[[:space:]]*=[[:space:]]*.*//')
      if ! grep -q "^$prop=" "$LOCAL_CONFIG"; then
        echo "ERROR: Required added property missing in local config: $prop. Please add it before proceeding."
        log_to_file "ERROR" "Required added property missing in local config: $prop. Please add it before proceeding."
        missing_flag=1
      fi
    elif [[ "$line" == optional:* ]]; then
      prop=$(echo "$line" | sed 's/^optional:[[:space:]]*//' | sed 's/[[:space:]]*=[[:space:]]*.*//')
      if ! grep -q "^$prop=" "$LOCAL_CONFIG"; then
        echo "WARNING: Newly added optional property missing in local config: $prop."
        log_to_file "WARNING" "Newly added optional property missing in local config: $prop."
        warning_flag=1
      fi
    else
      # Default to required if not specified
      prop=$(echo "$line" | sed 's/[[:space:]]*=[[:space:]]*.*//')
      if ! grep -q "^$prop=" "$LOCAL_CONFIG"; then
        echo "ERROR: Added property missing in local config: $prop. Please add it before proceeding."
        log_to_file "ERROR" "Added property missing in local config: $prop. Please add it before proceeding."
        missing_flag=1
      fi
    fi
  done < "$ADDED_FILE"
fi

if [ "$missing_flag" -eq 1 ]; then
  echo "One or more required properties are missing. Please update your local config and re-run the script."
  log_to_file "ERROR" "One or more required properties are missing. Please update your local config and re-run the script."
  exit 2
fi

if [ "$warning_flag" -eq 1 ]; then
  echo "Some warnings were displayed above. To acknowledge and proceed ..."
  log_to_file "WARNING" "Some warnings were displayed above. To acknowledge and proceed ..."
  echo "Type 'proceed' to continue:"
  read -r user_input
  while [[ ! "$user_input" =~ ^[Pp][Rr][Oo][Cc][Ee][Ee][Dd]$ ]]; do
    echo "Type 'proceed' to continue:"
    read -r user_input
  done
fi

echo "Config upgrade check passed. All required properties are present !"

# Call upgrade.sh from db_upgrade_script directory
SCRIPT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
UPGRADE_SCRIPT_PATH="$SCRIPT_ROOT/db_upgrade_script/mosip_certify/upgrade.sh"
PROPERTIES_FILE="$SCRIPT_ROOT/db_upgrade_script/mosip_certify/upgrade.properties"

echo "Please confirm that you have configured the properties file at: $PROPERTIES_FILE"
echo "Press Enter to continue..."
read -r

# For DB upgrade/rollback, log output and errors
if [ -f "$UPGRADE_SCRIPT_PATH" ]; then
  echo "Calling database upgrade script: $UPGRADE_SCRIPT_PATH"
  log_to_file "INFO" "Calling database upgrade script: $UPGRADE_SCRIPT_PATH"
  set +e
  bash "$UPGRADE_SCRIPT_PATH" "$PROPERTIES_FILE" 2>&1 | tee -a "$LOG_FILE"
  exit_code=${PIPESTATUS[0]}
  if [ $exit_code -ne 0 ]; then
    echo "Database upgrade failed with exit code $exit_code. Initiating rollback..."
    log_to_file "ERROR" "Database upgrade failed with exit code $exit_code. Initiating rollback..."
    ACTION=rollback bash "$UPGRADE_SCRIPT_PATH" "$PROPERTIES_FILE" 2>&1 | tee -a "$LOG_FILE"
    rollback_exit_code=${PIPESTATUS[0]}
    if [ $rollback_exit_code -ne 0 ]; then
      echo "Rollback also failed with exit code $rollback_exit_code. Manual intervention required."
      log_to_file "ERROR" "Rollback also failed with exit code $rollback_exit_code. Manual intervention required."
      exit $rollback_exit_code
    else
      echo "Rollback completed successfully."
      log_to_file "SUCCESS" "Rollback completed successfully."
      exit 1
    fi
  else
    # Only print success if upgrade did not fail and did not trigger rollback
    echo "Database upgrade completed successfully."
    log_to_file "SUCCESS" "Database upgrade completed successfully."
  fi
  set -e
else
  echo "Database upgrade script not found at $UPGRADE_SCRIPT_PATH. Skipping DB upgrade."
  log_to_file "WARNING" "Database upgrade script not found at $UPGRADE_SCRIPT_PATH. Skipping DB upgrade."
fi

set -e

exit 0
