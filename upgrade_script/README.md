# Inji Certify Config and DB Upgrade Script

> **Note:** This utility currently supports only the 0.11.0 to 0.12.0 version upgrade. It will support future releases as they are made available, but not past versions.

This script automates the process of validating configuration property files and performing database upgrades (with automatic rollback on failure) for Inji Certify deployments.

## Prerequisites
- Bash shell (Linux/macOS)
- PostgreSQL client (`psql`) installed and available in your PATH
- Proper permissions to execute shell scripts and access the database
- All required config and SQL files present in the expected directories

## Setup
1. **Prepare Config Files:**
   - Place your local config file (e.g., `local_config.properties`) in a known location.
   - **Do not modify the `deprecated.properties`, `modified.properties`, or `added.*.properties` files.** These are provided with each Inji Certify release and should be used as-is for validation.

2. **Prepare DB Upgrade Files:**
   - Place the required SQL upgrade and rollback scripts in `db_upgrade_script/mosip_certify/sql/`.
   - Ensure `upgrade.properties` is configured with correct DB credentials and parameters.

3. **Permissions:**
   - Make sure all scripts are executable:
     ```sh
     chmod +x upgrade_script/config_and_db_upgrade.sh
     chmod +x db_upgrade_script/mosip_certify/upgrade.sh
     ```

## How to Run
From the root of your project, execute:

```sh
./upgrade_script/config_and_db_upgrade.sh <local_config.properties> <from_version> <to_version>
```
- Example:
  ```sh
  ./upgrade_script/config_and_db_upgrade.sh ./my_local_config.properties 0.11.0 0.12.0
  ```

The script will:
- Read `mosip.certify.plugin-mode` from your local config file and select the correct `added.<plugin-mode>.properties` file for validation.
- Validate your config file against deprecated, modified, and added properties.
- Prompt you to confirm before proceeding with the DB upgrade.
- Run the DB upgrade script. If the upgrade fails, it will automatically attempt a rollback.
- Log all warnings, errors, and DB output to a log file.
- Display a success message only if the upgrade completes successfully (otherwise, errors and rollback status are shown).

## Types of Config Properties and Required Actions
| Property Type | Description | Action Required |
|--------------|-------------|----------------|
| **Added (required)**   | New property required for this version and plugin mode | Must be present in your local config before proceeding |
| **Added (optional)**   | New property, but optional | Recommended to add, but not mandatory |
| **Deprecated**         | Property is no longer used or supported | Remove from your local config to avoid warnings |
| **Modified**           | Property has changed meaning, format, or recommended value | Review and update as per release notes and inji-config repository |

**Summary:**
- The script will halt and display errors if any required properties are missing.
- Warnings for deprecated, modified, or missing optional properties will be shown and logged; you must type `proceed` to continue if warnings are present.
- All logs (including DB errors and warnings) are written to `upgrade_script/config_and_db_upgrade.log` and are overwritten on each run.

## Directory Structure
```
upgrade_script/
  config_and_db_upgrade.sh
  config_and_db_upgrade.log
  <from_version>_to_<to_version>/
    deprecated.properties
    modified.properties
    added.DataProvider.properties
    added.VCIssuance.properties

db_upgrade_script/
  mosip_certify/
    upgrade.sh
    upgrade.properties
    sql/
      <from_version>_to_<to_version>_upgrade.sql
      <from_version>_to_<to_version>_rollback.sql
```

## Where to Find Logs
- All output, warnings, and errors are logged to: `upgrade_script/config_and_db_upgrade.log`
- Review this file for troubleshooting and audit purposes after each run.
