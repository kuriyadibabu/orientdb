/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.core.sql;

import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OClassImpl;
import com.orientechnologies.orient.core.metadata.schema.OPropertyImpl;
import com.orientechnologies.orient.core.metadata.schema.OType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL CREATE PROPERTY command: Creates a new property in the target class.
 *
 * @author Luca Garulli
 * @author Michael MacFadden
 */
@SuppressWarnings("unchecked")
public class OCommandExecutorSQLCreateProperty extends OCommandExecutorSQLAbstract implements OCommandDistributedReplicateRequest {
  public static final String KEYWORD_CREATE   = "CREATE";
  public static final String KEYWORD_PROPERTY = "PROPERTY";
  
  public static final String KEYWORD_MANDATORY = "MANDATORY";
  public static final String KEYWORD_READONLY = "READONLY";
  public static final String KEYWORD_NOTNULL = "NOTNULL";
  public static final String KEYWORD_MIN = "MIN";
  public static final String KEYWORD_MAX = "MAX";
  public static final String KEYWORD_DEFAULT = "DEFAULT";
  public static final String KEYWORD_COLLATE = "COLLATE";
  public static final String KEYWORD_REGEX = "REGEX";

  private String             className;
  private String             fieldName;
  private OType              type;
  private String             linked;

  private boolean            readonly         = false;
  private boolean            mandatory        = false;
  private boolean            notnull          = false;

  private String             max              = null;
  private String             min              = null;
  private String             defaultValue     = null;
  private String             collate          = null;
  private String             regex            = null;

  private boolean            unsafe           = false;

  public OCommandExecutorSQLCreateProperty parse(final OCommandRequest iRequest) {

    final OCommandRequestText textRequest = (OCommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      init((OCommandRequestText) iRequest);

      final StringBuilder word = new StringBuilder();

      int oldPos = 0;
      int pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CREATE))
        throw new OCommandSQLParsingException("Keyword " + KEYWORD_CREATE + " not found", parserText, oldPos);

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_PROPERTY))
        throw new OCommandSQLParsingException("Keyword " + KEYWORD_PROPERTY + " not found", parserText, oldPos);

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1)
        throw new OCommandSQLParsingException("Expected <class>.<property>", parserText, oldPos);

      String[] parts = split(word);
      if (parts.length != 2)
        throw new OCommandSQLParsingException("Expected <class>.<property>", parserText, oldPos);

      className = decodeClassName(parts[0]);
      if (className == null)
        throw new OCommandSQLParsingException("Class not found", parserText, oldPos);
      fieldName = decodeClassName(parts[1]);

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1)
        throw new OCommandSQLParsingException("Missed property type", parserText, oldPos);

      type = OType.valueOf(word.toString());

      // Use a REGEX for the rest because we know exactly what we are looking for.
      // If we are in strict mode, the parser took care of strict matching.
      String rest = parserText.substring(pos).trim();
      String pattern = "(`[^`]*`|[^\\(]\\S*)?\\s*(\\(.*\\))?\\s*(UNSAFE)?";

      Pattern r = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
      Matcher m = r.matcher(rest.trim());

      if (m.matches()) {
        // Linked Type / Class
        if (m.group(1) != null && !m.group(1).equalsIgnoreCase("UNSAFE")) {
          linked = m.group(1);
          if (linked.startsWith("`") && linked.endsWith("`") && linked.length() > 1) {
            linked = linked.substring(1, linked.length() - 1);
          }
        }

        // Attributes
        if (m.group(2) != null) {
          String raw = m.group(2);
          String atts = raw.substring(1, raw.length() - 1);
          processAtts(atts);
        }

        // UNSAFE
        if (m.group(3) != null) {
          this.unsafe = true;
        }
      } else {
        // Syntax Error
      }
    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  private void processAtts(String atts) {
    String[] split = atts.split(",");
    for (String attDef: split) {
      String[] parts = attDef.trim().split("\\s+");
      if (parts.length > 2) {
        onInvalidAttributeDefinition(attDef);
      }
      
      String att = parts[0].trim();
      if (att.equalsIgnoreCase(KEYWORD_MANDATORY)) {
        this.mandatory = getOptionalBoolean(parts);
      } else if (att.equalsIgnoreCase(KEYWORD_READONLY)) {
        this.readonly = getOptionalBoolean(parts);
      } else if (att.equalsIgnoreCase(KEYWORD_NOTNULL)) {
        this.notnull = getOptionalBoolean(parts);
      } else if (att.equalsIgnoreCase(KEYWORD_MIN)) {
        this.min = getRequiredValue(attDef, parts);
      } else if (att.equalsIgnoreCase(KEYWORD_MAX)) {
        this.max = getRequiredValue(attDef, parts);
      } else if (att.equalsIgnoreCase(KEYWORD_DEFAULT)) {
        this.defaultValue = getRequiredValue(attDef, parts);
      } else if (att.equalsIgnoreCase(KEYWORD_COLLATE)) {
        this.collate = getRequiredValue(attDef, parts);
      } else if (att.equalsIgnoreCase(KEYWORD_REGEX)) {
        this.regex = getRequiredValue(attDef, parts);
      } else {
        onInvalidAttributeDefinition(attDef);
      }
    }
  }
  
  private void onInvalidAttributeDefinition(String attDef) {
    throw new OCommandSQLParsingException("Invalid attribute definition: '" + attDef + "'");
  }
  
  private boolean getOptionalBoolean(String[] parts) {
    if (parts.length < 2) {
      return true;
    }
    
    String trimmed = parts[1].trim();
    if (trimmed.length() == 0) {
      return true;
    }
    
    return Boolean.parseBoolean(trimmed);
  }
  
  private String getRequiredValue(String attDef, String[] parts) {
    if (parts.length < 2) {
      onInvalidAttributeDefinition(attDef);
    }
    
    String value = parts[1].trim();
    if (value.length() == 0) {
      onInvalidAttributeDefinition(attDef);
    }
    
    if (value.equalsIgnoreCase("null")) {
      value = null;
    }
    
    if (value != null && isQuoted(value)) {
      value = removeQuotes(value);
    }
    
    return value;
  }

  private String[] split(StringBuilder word) {
    List<String> result = new ArrayList<String>();
    StringBuilder builder = new StringBuilder();
    boolean quoted = false;
    for (char c : word.toString().toCharArray()) {
      if (!quoted) {
        if (c == '`') {
          quoted = true;
        } else if (c == '.') {
          String nextToken = builder.toString().trim();
          if (nextToken.length() > 0) {
            result.add(nextToken);
          }
          builder = new StringBuilder();
        } else {
          builder.append(c);
        }
      } else {
        if (c == '`') {
          quoted = false;
        } else {
          builder.append(c);
        }
      }
    }
    String nextToken = builder.toString().trim();
    if (nextToken.length() > 0) {
      result.add(nextToken);
    }
    return result.toArray(new String[] {});
  }

  @Override
  public long getDistributedTimeout() {
    return OGlobalConfiguration.DISTRIBUTED_COMMAND_QUICK_TASK_SYNCH_TIMEOUT.getValueAsLong();
  }

  /**
   * Execute the CREATE PROPERTY.
   */
  public Object execute(final Map<Object, Object> iArgs) {
    if (type == null)
      throw new OCommandExecutionException("Cannot execute the command because it has not been parsed yet");

    final ODatabaseDocument database = getDatabase();
    final OClassImpl sourceClass = (OClassImpl) database.getMetadata().getSchema().getClass(className);
    if (sourceClass == null)
      throw new OCommandExecutionException("Source class '" + className + "' not found");

    OPropertyImpl prop = (OPropertyImpl) sourceClass.getProperty(fieldName);
    if (prop != null)
      throw new OCommandExecutionException(
          "Property '" + className + "." + fieldName + "' already exists. Remove it before to retry.");

    // CREATE THE PROPERTY
    OClass linkedClass = null;
    OType linkedType = null;
    if (linked != null) {
      // FIRST SEARCH BETWEEN CLASSES
      linkedClass = database.getMetadata().getSchema().getClass(linked);

      if (linkedClass == null)
        // NOT FOUND: SEARCH BETWEEN TYPES
        linkedType = OType.valueOf(linked.toUpperCase(Locale.ENGLISH));
    }

    // CREATE IT LOCALLY
    OPropertyImpl internalProp = sourceClass.addPropertyInternal(fieldName, type, linkedType, linkedClass, unsafe);
    boolean toSave = false;
    if (readonly) {
      internalProp.setReadonly(true);
      toSave = true;
    }

    if (mandatory) {
      internalProp.setMandatory(true);
      toSave = true;
    }

    if (notnull) {
      internalProp.setNotNull(true);
      toSave = true;
    }

    if (max != null) {
      internalProp.setMax(max);
      toSave = true;
    }

    if (min != null) {
      internalProp.setMin(min);
      toSave = true;
    }

    if (defaultValue != null) {
      internalProp.setDefaultValue(defaultValue);
      toSave = true;
    }
    
    if (collate != null) {
      internalProp.setCollate(collate);
      toSave = true;
    }
    
    if (regex != null) {
      internalProp.setRegexp(regex);
      toSave = true;
    }

    if(toSave) {
      internalProp.save();
    }

    return sourceClass.properties().size();
  }
  
  private String removeQuotes(String s) {
    s = s.trim();
    return s.substring(1, s.length() - 1).replaceAll("\\\\\"", "\"");
  }
  
  private boolean isQuoted(String s) {
    s = s.trim();
    if (s.startsWith("\"") && s.endsWith("\""))
      return true;
    if (s.startsWith("'") && s.endsWith("'"))
      return true;
    if (s.startsWith("`") && s.endsWith("`"))
      return true;

    return false;
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.ALL;
  }

  @Override
  public String getUndoCommand() {
    return "drop property " + className + "." + fieldName;
  }

  @Override
  public String getSyntax() {
    return "CREATE PROPERTY <class>.<property> <type> [<linked-type>|<linked-class>] " +
        "[(mandatory <true|false>, notnull <true|false>, <true|false>, default <value>, min <value>, max <value>)] " + 
        "[UNSAFE]";
  }
}
