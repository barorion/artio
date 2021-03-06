/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.dictionary;

import org.agrona.Verify;
import org.w3c.dom.*;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.dictionary.ir.Field.Value;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static javax.xml.xpath.XPathConstants.NODESET;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.CHAR;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.STRING;

/**
 * Parses XML format dictionary files and into instances of
 * {@link uk.co.real_logic.artio.dictionary.ir.Dictionary}.
 */
public final class DictionaryParser
{
    private static final String FIELD_EXPR = "/fix/fields/field";
    private static final String MESSAGE_EXPR = "/fix/messages/message";
    private static final String COMPONENT_EXPR = "/fix/components/component";
    private static final String HEADER_EXPR = "/fix/header/field";
    private static final String TRAILER_EXPR = "/fix/trailer/field";

    private final DocumentBuilder documentBuilder;
    private final XPathExpression findField;
    private final XPathExpression findMessage;
    private final XPathExpression findComponent;
    private final XPathExpression findHeader;
    private final XPathExpression findTrailer;

    public DictionaryParser()
    {
        try
        {
            documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

            final XPath xPath = XPathFactory.newInstance().newXPath();
            findField = xPath.compile(FIELD_EXPR);
            findMessage = xPath.compile(MESSAGE_EXPR);
            findComponent = xPath.compile(COMPONENT_EXPR);
            findHeader = xPath.compile(HEADER_EXPR);
            findTrailer = xPath.compile(TRAILER_EXPR);
        }
        catch (final ParserConfigurationException | XPathExpressionException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public Dictionary parse(final InputStream in) throws Exception
    {
        final Document document = documentBuilder.parse(in);
        final Map<String, Field> fields = parseFields(document);
        final Map<Entry, String> forwardReferences = new HashMap<>();
        final Map<String, Component> components = parseComponents(document, fields, forwardReferences);
        final List<Message> messages = parseMessages(document, fields, components, forwardReferences);
        final Component header = extractComponent(
            document, fields, findHeader, "Header", components, forwardReferences);
        final Component trailer = extractComponent(
            document, fields, findTrailer, "Trailer", components, forwardReferences);

        reconnectForwardReferences(forwardReferences, components);

        final NamedNodeMap fixAttributes = document.getElementsByTagName("fix").item(0).getAttributes();
        final int majorVersion = getInt(fixAttributes, "major");
        final int minorVersion = getInt(fixAttributes, "minor");

        simplifyComponentsThatAreJustGroups(components, messages);
        correctMultiCharacterCharEnums(fields);

        return new Dictionary(messages, fields, components, header, trailer, majorVersion, minorVersion);
    }

    private void correctMultiCharacterCharEnums(final Map<String, Field> fields)
    {
        fields.values()
            .stream()
            .filter(Field::isEnum)
            .filter(field -> field.type() == CHAR)
            .filter(this::hasMultipleCharacters)
            .forEach(field -> field.type(STRING));
    }

    private boolean hasMultipleCharacters(final Field field)
    {
        return field.values().stream().anyMatch(value -> value.representation().length() > 1);
    }

    private void simplifyComponentsThatAreJustGroups(final Map<String, Component> components,
        final List<Message> messages)
    {
        final List<String> toRemove = new ArrayList<>();
        components.forEach((name, component) ->
        {
            if (isJustGroup(component))
            {
                toRemove.add(name);
                final Entry.Element group = extractFirst(component);
                messages.forEach(aggregate -> replaceComponent(aggregate, component, group));
                components.values().forEach(aggregate -> replaceComponent(aggregate, component, group));
            }
        });

        toRemove.forEach(components::remove);
    }

    private void replaceComponent(
        final Aggregate aggregate,
        final Component component,
        final Entry.Element group)
    {
        aggregate.entriesWith(element -> element == component)
            .forEach((entry) -> entry.element(group));

        aggregate.entriesWith(element -> element instanceof Aggregate)
            .forEach((entry) -> replaceComponent((Aggregate)entry.element(), component, group));
    }

    private Entry.Element extractFirst(final Component component)
    {
        return component.entries().get(0).element();
    }

    private boolean isJustGroup(final Component component)
    {
        final List<Entry> entries = component.entries();
        return entries.size() == 1 && entries.get(0).element() instanceof Group;
    }

    private void reconnectForwardReferences(final Map<Entry, String> forwardReferences,
        final Map<String, Component> components)
    {
        forwardReferences.forEach((entry, name) ->
        {
            final Component component = components.get(name);
            entry.element(component);
        });
    }

    private Map<String, Component> parseComponents(
        final Document document,
        final Map<String, Field> fields,
        final Map<Entry, String> forwardReferences)
        throws XPathExpressionException
    {
        final Map<String, Component> components = new HashMap<>();
        extractNodes(document, findComponent,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String name = name(attributes);
                final Component component = new Component(name);

                extractEntries(node.getChildNodes(), fields, component.entries(), components, forwardReferences);

                components.put(name, component);
            });

        return components;
    }

    private Map<String, Field> parseFields(final Document document) throws XPathExpressionException
    {
        final HashMap<String, Field> fields = new HashMap<>();
        extractNodes(document, findField,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();

                final String name = name(attributes);
                final int number = getInt(attributes, "number");
                final Type type = Type.lookup(getValue(attributes, "type"));
                final Field field = new Field(number, name, type);

                extractEnumValues(field.values(), node.getChildNodes());
                fields.put(name, field);
            });

        return fields;
    }

    private int getInt(final NamedNodeMap attributes, final String attributeName)
    {
        return Integer.parseInt(getValue(attributes, attributeName));
    }

    private void extractEnumValues(final List<Value> values, final NodeList childNodes)
    {
        forEach(childNodes,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String representation = getValue(attributes, "enum");
                final String description = getValue(attributes, "description");
                values.add(new Value(representation, description));
            });
    }

    private List<Message> parseMessages(
        final Document document,
        final Map<String, Field> fields,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences) throws XPathExpressionException
    {
        final ArrayList<Message> messages = new ArrayList<>();

        extractNodes(document, findMessage,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();

                final String name = name(attributes);
                final String fullType = getValue(attributes, "msgtype");
                final Category category = parseCategory(getValue(attributes, "msgcat"));
                final Message message = new Message(name, fullType, category);

                extractEntries(node.getChildNodes(), fields, message.entries(), components, forwardReferences);

                messages.add(message);
            });

        return messages;
    }

    private void extractEntries(
        final NodeList childNodes,
        final Map<String, Field> fields,
        final List<Entry> entries,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences)
    {
        forEach(childNodes,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String name = name(attributes);
                if (name.trim().length() == 0)
                {
                    return;
                }

                final boolean required = isRequired(attributes);
                final Consumer<Entry.Element> newEntry =
                    (element) ->
                    {
                        Verify.notNull(element, "element for " + name);
                        entries.add(new Entry(required, element));
                    };

                switch (node.getNodeName())
                {
                    case "field":
                        newEntry.accept(fields.get(name));
                        break;

                    case "group":
                        final Group group = Group.of(fields.get(name));
                        extractEntries(node.getChildNodes(), fields, group.entries(), components, forwardReferences);
                        newEntry.accept(group);
                        break;

                    case "component":
                        final Component component = components.get(name);
                        final Entry entry = new Entry(required, component);
                        if (component == null)
                        {
                            forwardReferences.put(entry, name);
                        }
                        entries.add(entry);
                        break;
                }
            });
    }

    private Component extractComponent(
        final Document document,
        final Map<String, Field> fields,
        final XPathExpression expression,
        final String name,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences)
        throws XPathExpressionException
    {
        final Component component = new Component(name);
        final NodeList nodes = evaluate(document, expression);
        extractEntries(nodes, fields, component.entries(), components, forwardReferences);

        return component;
    }

    private String name(final NamedNodeMap attributes)
    {
        return getValue(attributes, "name");
    }

    private boolean isRequired(final NamedNodeMap attributes)
    {
        return "Y".equals(getValue(attributes, "required"));
    }

    private Category parseCategory(final String from)
    {
        return Category.valueOf(from.toUpperCase());
    }

    private String getValue(final NamedNodeMap attributes, final String attributeName)
    {
        return attributes.getNamedItem(attributeName).getNodeValue();
    }

    private void extractNodes(
        final Document document, final XPathExpression expression, final Consumer<Node> handler)
        throws XPathExpressionException
    {
        forEach(evaluate(document, expression), handler);
    }

    private NodeList evaluate(final Document document, final XPathExpression expression) throws XPathExpressionException
    {
        return (NodeList)expression.evaluate(document, NODESET);
    }

    private void forEach(final NodeList nodes, final Consumer<Node> handler)
    {
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Node node = nodes.item(i);
            if (node instanceof Element)
            {
                handler.accept(node);
            }
        }
    }
}
