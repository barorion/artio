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
package uk.co.real_logic.artio.dictionary.generation;

import org.agrona.collections.IntHashSet;
import org.agrona.generation.StringWriterOutputManager;
import org.junit.BeforeClass;
import org.junit.Test;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.dictionary.ExampleDictionary;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampDecoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.util.Reflection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.builder.Decoder.NO_ERROR;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.DecoderGenerator.*;
import static uk.co.real_logic.artio.fields.DecimalFloat.MISSING_FLOAT;
import static uk.co.real_logic.artio.util.Reflection.*;

public class DecoderGeneratorTest
{
    private static final char[] ABC = "abc".toCharArray();
    private static final char[] AB = "ab".toCharArray();
    private static final String ON_BEHALF_OF_COMP_ID = "onBehalfOfCompID";

    private static Class<?> heartbeatWithoutValidation;
    private static Class<?> heartbeat;
    private static Class<?> component;
    private static Class<?> otherMessage;
    private static Class<?> fieldsMessage;

    private MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[8 * 1024]);

    @BeforeClass
    public static void generate() throws Exception
    {
        final Map<String, CharSequence> sourcesWithValidation = generateSources(true);
        final Map<String, CharSequence> sourcesWithoutValidation = generateSources(false);
        heartbeat = compileInMemory(HEARTBEAT_DECODER, sourcesWithValidation);
        if (heartbeat == null || CODEC_LOGGING)
        {
            System.out.println("sourcesWithValidation = " + sourcesWithValidation);
        }
        component = heartbeat.getClassLoader().loadClass(COMPONENT_DECODER);
        fieldsMessage = heartbeat.getClassLoader().loadClass(FIELDS_MESSAGE_DECODER);
        compileInMemory(HEADER_DECODER, sourcesWithValidation);
        otherMessage = compileInMemory(OTHER_MESSAGE_DECODER, sourcesWithValidation);

        heartbeatWithoutValidation = compileInMemory(HEARTBEAT_DECODER, sourcesWithoutValidation);
        if (heartbeatWithoutValidation == null || CODEC_LOGGING)
        {
            System.out.println("sourcesWithoutValidation = " + sourcesWithoutValidation);
        }
    }

    private static Map<String, CharSequence> generateSources(final boolean validation)
    {
        final Class<?> validationClass = validation ? ValidationOn.class : ValidationOff.class;
        final StringWriterOutputManager outputManager = new StringWriterOutputManager();
        final ConstantGenerator constantGenerator = new ConstantGenerator(
            MESSAGE_EXAMPLE, TEST_PACKAGE, outputManager);
        final DecoderGenerator decoderGenerator = new DecoderGenerator(
            MESSAGE_EXAMPLE, 1, TEST_PACKAGE, outputManager, validationClass);

        constantGenerator.generate();
        decoderGenerator.generate();
        return outputManager.getSources();
    }

    @Test
    public void generatesDecoderClass() throws Exception
    {
        assertNotNull("Not generated anything", heartbeat);
        assertIsDecoder(heartbeat);

        final int modifiers = heartbeat.getModifiers();
        assertFalse("Not instantiable", isAbstract(modifiers));
        assertTrue("Not public", isPublic(modifiers));
    }

    @Test
    public void generatesGetters() throws NoSuchMethodException
    {
        final Method onBehalfOfCompID = heartbeat.getMethod(ON_BEHALF_OF_COMP_ID);
        assertEquals(char[].class, onBehalfOfCompID.getReturnType());
    }

    @Test
    public void stringGettersReadFromFields() throws Exception
    {
        final Decoder decoder = (Decoder)heartbeat.newInstance();
        setField(decoder, ON_BEHALF_OF_COMP_ID, ABC);
        setField(decoder, ON_BEHALF_OF_COMP_ID + "Length", 3);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
    }

    @Test
    public void flagsForOptionalFieldsInitiallyUnset() throws Exception
    {
        final Object decoder = heartbeat.newInstance();
        assertFalse("hasTestReqId initially true", hasTestReqId(decoder));
    }

    @Test(expected = InvocationTargetException.class)
    public void missingOptionalFieldCausesGetterToThrow() throws Exception
    {
        final Object decoder = heartbeat.newInstance();

        Reflection.get(decoder, TEST_REQ_ID);
    }

    @Test
    public void decodesValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldIgnoreMissingOptionalValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void setsMissingOptionalValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        assertTrue(hasTestReqId(decoder));
        assertTrue(hasBooleanField(decoder));
        assertTrue(hasDataField(decoder));

        assertArrayEquals(ABC, getTestReqId(decoder));
        assertEquals(true, getBooleanField(decoder));
        assertArrayEquals(new byte[]{ '1', '2', '3' }, getDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void hasMessageTypeFlag() throws Exception
    {
        final int messageType = (int)getStatic(heartbeat, "MESSAGE_TYPE");

        assertEquals(HEARTBEAT_TYPE, messageType);
    }

    @Test
    public void decodesCommonComponents() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        final Decoder header = getHeader(decoder);
        assertEquals(75, getBodyLength(header));

        final Decoder trailer = getTrailer(decoder);
        assertEquals("199", getChecksum(trailer));
    }

    @Test
    public void shouldResetFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        decoder.reset();

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));
        assertFalse(hasComponentField(decoder));
        assertFalse(hasNoEgGroupGroupCounter(decoder));

        assertEquals(MISSING_FLOAT, getFloatField(decoder));
        assertEquals(MISSING_INT, getIntField(decoder));
    }

    @Test
    public void shouldGenerateHumanReadableToString() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        assertThat(decoder.toString(), containsString(STRING_NO_OPTIONAL_MESSAGE_EXAMPLE));

        assertValid(decoder);
    }

    @Test
    public void shouldIncludeOptionalFieldsInToString() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        assertThat(decoder.toString(), containsString(STRING_ENCODED_MESSAGE_EXAMPLE));
    }

    @Test
    public void shouldDecodeShorterStringsAfterLongerStrings() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));

        decode(SHORTER_STRING_MESSAGE, decoder);

        assertArrayEquals(AB, getOnBehalfOfCompId(decoder));
    }

    @Test
    public void shouldToStringShorterStringsAfterLongerStrings() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        decode(SHORTER_STRING_MESSAGE, decoder);

        assertThat(decoder.toString(), containsString("\"OnBehalfOfCompID\": \"ab\","));
    }

    @Test
    public void shouldDecodeRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsAfterReset() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(REPEATING_GROUP_MESSAGE, decoder);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeShorterRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeShorterRepeatingGroupsAfterReset() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeNestedRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NESTED_GROUP_MESSAGE);

        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(1, getGroupField(group));
        assertNull(next(group));

        final Object nestedGroup = getNestedGroup(group);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder$NestedGroupGroupDecoder",
            nestedGroup.getClass().getName());
        assertEquals(1, get(nestedGroup, "nestedField"));
        assertNull(next(nestedGroup));

        assertValid(decoder);
    }

    @Test
    public void shouldToStringRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertThat(decoder, hasToString(containsString(STRING_GROUP_TWO_ELEMENTS)));
    }

    @Test
    public void shouldDecodeComponents() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(COMPONENT_MESSAGE);

        assertEquals(2, get(decoder, "componentField"));

        assertValid(decoder);
    }

    @Test
    public void shouldGenerateComponentToString() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(COMPONENT_MESSAGE);

        assertThat(decoder.toString(), containsString("  \"ComponentField\": \"2\""));
    }

    @Test
    public void shouldGenerateComponentInterface() throws Exception
    {
        assertTrue(
            "heartbeat doesn't implement its component",
            component.isAssignableFrom(heartbeat));

        assertHasComponentFieldGetter();
    }

    @Test
    public void shouldGenerateRequiredFieldsDictionary() throws Exception
    {
        final Decoder decoder = (Decoder)heartbeat.newInstance();
        final Object allFieldsField = getRequiredFields(decoder);
        assertThat(allFieldsField, instanceOf(IntHashSet.class));

        @SuppressWarnings("unchecked") final Set<Integer> allFields = (Set<Integer>)allFieldsField;
        assertThat(allFields, hasItem(116));
        assertThat(allFields, not(hasItem(112)));
        assertThat(allFields, not(hasItem(999)));
    }

    @Test
    public void shouldValidateMissingRequiredFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MISSING_REQUIRED_FIELDS_MESSAGE);

        assertFalse("Passed validation with missing fields", decoder.validate());
        assertEquals("Wrong tag id", 116, decoder.invalidTagId());
        assertEquals("Wrong reject reason", REQUIRED_TAG_MISSING, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagNumbers() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INVALID_TAG_NUMBER_MESSAGE);

        assertFalse("Passed validation with invalid tag number", decoder.validate());
        assertEquals("Wrong tag id", 9999, decoder.invalidTagId());
        assertEquals("Wrong reject reason", INVALID_TAG_NUMBER, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagNumbersDefinedForThisMessage() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE_MESSAGE);

        assertFalse("Passed validation with invalid tag number", decoder.validate());
        assertEquals("Wrong tag id", 99, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagSpecifiedWithMissingValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WITHOUT_A_VALUE_MESSAGE);

        assertFalse("Passed validation with missing value", decoder.validate());
        assertEquals("Wrong tag id", 116, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_SPECIFIED_WITHOUT_A_VALUE, decoder.rejectReason());
    }

    @Test
    public void shouldValidateIntBasedEnum() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WHERE_INT_VALUE_IS_INCORRECT_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 116, decoder.invalidTagId());
        assertEquals("Wrong reject reason", VALUE_IS_INCORRECT, decoder.rejectReason());
    }

    @Test
    public void shouldValidateStringBasedEnum() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WHERE_STRING_VALUE_IS_INCORRECT_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 115, decoder.invalidTagId());
        assertEquals("Wrong reject reason", VALUE_IS_INCORRECT, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagsAppearingMoreThanOnce() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_APPEARS_MORE_THAN_ONCE_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 116, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_APPEARS_MORE_THAN_ONCE, decoder.rejectReason());
    }

    @Test
    public void shouldResetTheInvalidAccessors() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_APPEARS_MORE_THAN_ONCE_MESSAGE);
        decoder.validate();

        decoder.reset();

        assertEquals("Failed to reset tag id", NO_ERROR, decoder.invalidTagId());
        assertEquals("Failed to reset reject reason", NO_ERROR, decoder.rejectReason());
    }

    @Test
    public void shouldValidateFirstThreeFieldsAreInOrder() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 9, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER, decoder.rejectReason());
    }

    @Test
    public void shouldBeAbleToExtractStringsFromStringFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        assertEquals("abc", get(decoder, "onBehalfOfCompIDAsString"));
        assertNull(get(decoder, "testReqIDAsString"));
    }

    @Test
    public void shouldProduceCorrectMessageTypeForTwoCharTypes() throws Exception
    {
        final byte[] messageTypeBytes = (byte[])getStatic(otherMessage, "MESSAGE_TYPE_BYTES");
        final int messageTypePacked = (int)getStatic(otherMessage, "MESSAGE_TYPE");

        assertEquals(OTHER_MESSAGE_TYPE_PACKED, messageTypePacked);
        assertArrayEquals(OTHER_MESSAGE_TYPE_BYTES, messageTypeBytes);
    }

    @Test
    public void shouldDecodeShortTimestampMessageCorrectly() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(SHORT_TIMESTAMP_MESSAGE);

        final byte[] someTime = getSomeTimeField(decoder);
        final UtcTimestampDecoder someTimeDecoder = new UtcTimestampDecoder();
        final long someTimeValue = someTimeDecoder.decode(someTime, someTime.length);
        assertEquals(0, someTimeValue);

        final String someTimeFieldAsString = getSomeTimeFieldAsString(decoder);
        assertEquals("19700101-00:00:00", someTimeFieldAsString);
    }

    @Test
    public void shouldGenerateIteratorForRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        canIterateOverGroup(decoder);

        canIterateOverGroup(decoder);
    }

    @Test
    public void shouldGenerateAllFieldsSet() throws Exception
    {
        final Decoder decoder = (Decoder)heartbeat.newInstance();
        final Object allFieldsField = getField(decoder, ALL_FIELDS);
        assertThat(allFieldsField, instanceOf(IntHashSet.class));

        @SuppressWarnings("unchecked") final Set<Integer> allFields = (Set<Integer>)allFieldsField;
        assertThat(allFields, hasItem(123));
        assertThat(allFields, hasItem(124));
        assertThat(allFields, hasItem(35));
        assertThat(allFields, not(hasItem(999)));
    }

    @Test
    public void shouldDecodeDifferentFieldTypes() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.newInstance();
        decode(EG_FIELDS_MESSAGE, decoder);

        assertRequiredFieldsMessageFieldsDecoded(decoder, "GBP", "XLON", "GB");

        final char[] gbp = "GBP".toCharArray();
        final char[] xlon = "XLON".toCharArray();
        final char[] gb = "GB".toCharArray();

        assertArrayEquals(gbp, getOptionalCurrencyField(decoder));
        assertArrayEquals(xlon, getOptionalExchangeField(decoder));
        assertArrayEquals(gb, getOptionalCountryField(decoder));

        assertEquals("GBP", getOptionalCurrencyFieldAsString(decoder));
        assertEquals("XLON", getOptionalExchangeFieldAsString(decoder));
        assertEquals("GB", getOptionalCountryFieldAsString(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeDifferentFieldTypesWithoutOptionalFields() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.newInstance();
        decode(EG_NO_OPTIONAL_FIELDS_MESSAGE, decoder);

        assertRequiredFieldsMessageFieldsDecoded(decoder, "USD", "N", "US");

        assertOptionalDifferentFieldsNotDecoded(decoder);

        assertValid(decoder);
    }

    @Test
    public void shouldResetDifferentFieldTypes() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.newInstance();
        decode(EG_FIELDS_MESSAGE, decoder);

        decoder.reset();

        assertRequiredFieldsMessageFieldsAsStringDecoded(decoder, "", "", "");
        assertOptionalDifferentFieldsNotDecoded(decoder);

        decode(EG_NO_OPTIONAL_FIELDS_MESSAGE, decoder);
        assertRequiredFieldsMessageFieldsDecoded(decoder, "USD", "N", "US");
        assertOptionalDifferentFieldsNotDecoded(decoder);

        assertValid(decoder);
    }

    // -----------------------------------------------------
    //             Validation Off Test Cases
    // -----------------------------------------------------

    @Test
    public void shouldIgnoreMissingRequiredFieldsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(MISSING_REQUIRED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        // Missing int field
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));
    }

    @Test
    public void shouldIgnoreMissingRequiredFieldsWithRepeatingGroupWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(
            REPEATING_GROUP_MESSAGE_WITH_MISSING_REQUIRED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        // Missing int field
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldIgnoreInvalidTagNumberWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(INVALID_TAG_NUMBER_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));
    }

    @Test
    public void shouldIgnoreInvalidTagNumberInGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER);

        assertRepeatingGroupAndFieldsDecoded(decoder);
    }

    @Test
    public void shouldIgnoreInvalidTagNumberInGroupsFieldAfterWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(
            REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER_FIELDS_AFTER);

        assertRepeatingGroupAndFieldsDecoded(decoder);
    }

    @Test
    public void decodesValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldIgnoreMissingOptionalValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(DERIVED_FIELDS_MESSAGE);

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void setsMissingOptionalValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(ENCODED_MESSAGE);

        assertTrue(hasTestReqId(decoder));
        assertTrue(hasBooleanField(decoder));
        assertTrue(hasDataField(decoder));

        assertArrayEquals(ABC, getTestReqId(decoder));
        assertEquals(true, getBooleanField(decoder));
        assertArrayEquals(new byte[]{ '1', '2', '3' }, getDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsAfterResetWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(REPEATING_GROUP_MESSAGE, decoder);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeNestedRepeatingGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(NESTED_GROUP_MESSAGE);

        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(1, getGroupField(group));
        assertNull(next(group));

        final Object nestedGroup = getNestedGroup(group);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder$NestedGroupGroupDecoder",
            nestedGroup.getClass().getName());
        assertEquals(1, get(nestedGroup, "nestedField"));
        assertNull(next(nestedGroup));

        assertValid(decoder);
    }

    private void assertRepeatingGroupAndFieldsDecoded(final Decoder decoder) throws Exception
    {
        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValidRepeatingGroupDecoded(decoder);
    }

    private void assertOptionalDifferentFieldsNotDecoded(final Decoder decoder) throws Exception
    {
        assertNull(getOptionalCurrencyFieldAsString(decoder));
        assertNull(getOptionalExchangeFieldAsString(decoder));
        assertNull(getOptionalCountryFieldAsString(decoder));
    }

    private void assertRequiredFieldsMessageFieldsDecoded(
        final Decoder decoder, final String currency, final String exchange, final String country) throws Exception
    {
        final char[] currencyChars = currency.toCharArray();
        final char[] exchangeChars = exchange.toCharArray();
        final char[] countryChars = country.toCharArray();

        final int currencyFieldLength = getCurrencyFieldLength(decoder);
        final int exchangeFieldLength = getExchangeFieldLength(decoder);
        final int countryFieldLength = getCountryFieldLength(decoder);

        assertEquals(currencyChars.length, currencyFieldLength);
        assertEquals(exchangeChars.length, exchangeFieldLength);
        assertEquals(countryChars.length, countryFieldLength);

        assertArrayEquals(currencyChars, Arrays.copyOf(getCurrencyField(decoder), currencyFieldLength));
        assertArrayEquals(exchangeChars, Arrays.copyOf(getExchangeField(decoder), exchangeFieldLength));
        assertArrayEquals(countryChars, Arrays.copyOf(getCountryField(decoder), countryFieldLength));

        assertRequiredFieldsMessageFieldsAsStringDecoded(decoder, currency, exchange, country);
    }

    private void assertRequiredFieldsMessageFieldsAsStringDecoded(
        final Decoder decoder, final String currency, final String exchange, final String country) throws Exception
    {
        assertEquals(currency, getCurrencyFieldAsString(decoder));
        assertEquals(exchange, getExchangeFieldAsString(decoder));
        assertEquals(country, getCountryFieldAsString(decoder));
    }

    private String getOptionalCountryFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalCountryFieldAsString");
    }

    private String getOptionalExchangeFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalExchangeFieldAsString");
    }

    private String getOptionalCurrencyFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalCurrencyFieldAsString");
    }

    private String getCountryFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "countryFieldAsString");
    }

    private String getExchangeFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "exchangeFieldAsString");
    }

    private String getCurrencyFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "currencyFieldAsString");
    }

    private char[] getOptionalCountryField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalCountryField");
    }

    private char[] getOptionalExchangeField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalExchangeField");
    }

    private char[] getOptionalCurrencyField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalCurrencyField");
    }

    private char[] getCountryField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "countryField");
    }

    private char[] getExchangeField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "exchangeField");
    }

    private char[] getCurrencyField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "currencyField");
    }

    private int getCurrencyFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "currencyFieldLength");
    }

    private int getExchangeFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "exchangeFieldLength");
    }

    private int getCountryFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "countryFieldLength");
    }

    // TODO: validation for groups

    private void canIterateOverGroup(final Decoder decoder) throws Exception
    {
        final Iterator<?> iterator = getEgGroupIterator(decoder);

        assertTrue(iterator.hasNext());
        Object group = iterator.next();
        assertEquals(1, getGroupField(group));

        assertTrue(iterator.hasNext());
        group = iterator.next();
        assertEquals(2, getGroupField(group));

        assertFalse(iterator.hasNext());
    }

    private void assertValidRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertRepeatingGroupDecoded(decoder);

        assertValid(decoder);
    }

    private void assertRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertEquals(2, getNoEgGroupGroupCounter(decoder));

        Object group = getEgGroup(decoder);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder",
            group.getClass().getName());
        assertEquals(1, getGroupField(group));

        group = next(group);
        assertEquals(2, getGroupField(group));
        assertNull(next(group));
    }

    private void assertSingleRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(2, getGroupField(group));

        assertValid(decoder);
    }

    private Object getStatic(final Class<?> cls, final String field) throws IllegalAccessException, NoSuchFieldException
    {
        return cls.getField(field).get(null);
    }

    private void assertHasComponentFieldGetter() throws NoSuchMethodException, ClassNotFoundException
    {
        assertHasMethod("componentField", int.class);
        assertHasMethod(HAS_COMPONENT_FIELD, boolean.class);

        assertHasMethod(
            "componentGroupGroup",
            heartbeat.getClassLoader().loadClass(
                "uk.co.real_logic.artio.builder.test.EgComponentDecoder$ComponentGroupGroupDecoder"));
    }

    private void assertHasMethod(final String name, final Class<?> expectedReturnType) throws NoSuchMethodException
    {
        final Method method = component.getMethod(name);
        assertEquals(expectedReturnType, method.getReturnType());
    }

    private int getNoEgGroupGroupCounter(final Decoder decoder) throws Exception
    {
        return (int)get(decoder, "noEgGroupGroupCounter");
    }

    private boolean hasNoEgGroupGroupCounter(final Decoder decoder) throws Exception
    {
        return (boolean)get(decoder, "hasNoEgGroupGroupCounter");
    }

    private int getGroupField(final Object group) throws Exception
    {
        return (int)get(group, "groupField");
    }

    private int getBodyLength(final Decoder header) throws Exception
    {
        return (int)get(header, ExampleDictionary.BODY_LENGTH);
    }

    private Decoder getTrailer(final Decoder trailer) throws Exception
    {
        return (Decoder)get(trailer, "trailer");
    }

    private String getChecksum(final Decoder trailer) throws Exception
    {
        return (String)get(trailer, "checkSumAsString");
    }

    private Decoder getHeader(final Decoder decoder) throws Exception
    {
        return (Decoder)get(decoder, "header");
    }

    private Decoder decodeHeartbeat(final String example) throws Exception
    {
        final Decoder decoder = (Decoder)heartbeat.newInstance();
        decode(example, decoder);
        return decoder;
    }

    private Decoder decodeHeartbeatWithoutValidation(final String example) throws Exception
    {
        final Decoder decoder = (Decoder)heartbeatWithoutValidation.newInstance();
        decode(example, decoder);
        return decoder;
    }

    private void decode(final String example, final Decoder decoder)
    {
        buffer.putAscii(1, example);
        decoder.decode(buffer, 1, example.length());
    }

    private void assertIsDecoder(final Class<?> cls)
    {
        assertTrue("Isn't a decoder", Decoder.class.isAssignableFrom(cls));
    }

    private boolean hasTestReqId(final Object encoder) throws Exception
    {
        return (boolean)getField(encoder, HAS_TEST_REQ_ID);
    }

    private boolean hasDataField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_DATA_FIELD);
    }

    private boolean hasComponentField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_COMPONENT_FIELD);
    }

    private boolean hasBooleanField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_BOOLEAN_FIELD);
    }

    private Object getFloatField(final Decoder decoder) throws Exception
    {
        return get(decoder, FLOAT_FIELD);
    }

    private Object getIntField(final Object decoder) throws Exception
    {
        return get(decoder, INT_FIELD);
    }

    private char[] getOnBehalfOfCompId(final Decoder decoder) throws Exception
    {
        return getCharArray(decoder, ON_BEHALF_OF_COMP_ID);
    }

    private byte[] getDataField(final Decoder decoder) throws Exception
    {
        return getBytes(decoder, DATA_FIELD);
    }

    private byte[] getSomeTimeField(final Decoder decoder) throws Exception
    {
        return getBytes(decoder, SOME_TIME_FIELD);
    }

    private String getSomeTimeFieldAsString(final Decoder decoder) throws Exception
    {
        return (String)get(decoder, SOME_TIME_FIELD + "AsString");
    }

    private Object getBooleanField(final Decoder decoder) throws Exception
    {
        return get(decoder, BOOLEAN_FIELD);
    }

    private char[] getTestReqId(final Decoder decoder) throws Exception
    {
        return getCharArray(decoder, TEST_REQ_ID);
    }

    private char[] getCharArray(final Decoder decoder, final String name) throws Exception
    {
        final char[] value = (char[])get(decoder, name);
        final int length = (int)get(decoder, name + "Length");
        return Arrays.copyOf(value, length);
    }

    private void assertValid(final Decoder decoder)
    {
        final boolean isValid = decoder.validate();
        assertTrue(String.format(
            "Decoder fails validation due to: %s for tag: %d", decoder.rejectReason(), decoder.invalidTagId()),
            isValid);
    }

    private Object getRequiredFields(final Decoder decoder) throws IllegalAccessException, NoSuchFieldException
    {
        return heartbeat.getField(REQUIRED_FIELDS).get(decoder);
    }
}
