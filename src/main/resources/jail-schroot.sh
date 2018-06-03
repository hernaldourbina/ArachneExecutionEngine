JAIL=$1
ANALYSIS_ENTRY_FILE=$2

SESSION=$(schroot -b -p -c ee-r-env)
schroot -r -c $SESSION -u root --directory="/$JAIL" -- /usr/bin/env -i DBMS_USERNAME=$DBMS_USERNAME \
 DBMS_PASSWORD=$DBMS_PASSWORD DBMS_TYPE=$DBMS_TYPE \
 CONNECTION_STRING=$CONNECTION_STRING DBMS_SCHEMA=$DBMS_SCHEMA \
 TARGET_SCHEMA=$TARGET_SCHEMA RESULT_SCHEMA=$RESULT_SCHEMA \
 COHORT_TARGET_TABLE=$COHORT_TARGET_TABLE PATH=$PATH \
 HOME=$HOME \
 Rscript $ANALYSIS_ENTRY_FILE
schroot -e -c $SESSION