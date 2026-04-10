package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.DelimiterInfo;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Dedicated FIX Protocol parser with full tag dictionary and message-type awareness.
 *
 * FIX uses tag=value pairs separated by SOH (0x01) character.
 * In logs/display, SOH is often represented as pipe '|'.
 *
 * Key tags:
 *   8  = BeginString (FIX version)
 *   9  = BodyLength
 *   35 = MsgType (D=NewOrder, 8=ExecutionReport, 0=Heartbeat, etc.)
 *   49 = SenderCompID
 *   56 = TargetCompID
 *   34 = MsgSeqNum
 *   52 = SendingTime
 *   10 = CheckSum (last tag)
 *
 * Order-related:
 *   11 = ClOrdID
 *   55 = Symbol
 *   54 = Side (1=Buy, 2=Sell)
 *   38 = OrderQty
 *   40 = OrdType (1=Market, 2=Limit, 3=Stop)
 *   44 = Price
 *   59 = TimeInForce (0=Day, 1=GTC, 2=OPG, 3=IOC, 4=FOK)
 */
@Component
@Slf4j
public class FixParser {

    private static final Map<String, String> MSG_TYPES = Map.ofEntries(
            Map.entry("0", "Heartbeat"),
            Map.entry("1", "Test Request"),
            Map.entry("2", "Resend Request"),
            Map.entry("3", "Reject"),
            Map.entry("4", "Sequence Reset"),
            Map.entry("5", "Logout"),
            Map.entry("8", "Execution Report"),
            Map.entry("9", "Order Cancel Reject"),
            Map.entry("A", "Logon"),
            Map.entry("D", "New Order Single"),
            Map.entry("E", "New Order List"),
            Map.entry("F", "Order Cancel Request"),
            Map.entry("G", "Order Cancel/Replace Request"),
            Map.entry("H", "Order Status Request"),
            Map.entry("J", "Allocation Instruction"),
            Map.entry("R", "Quote Request"),
            Map.entry("S", "Quote"),
            Map.entry("V", "Market Data Request"),
            Map.entry("W", "Market Data Snapshot"),
            Map.entry("X", "Market Data Incremental Refresh"),
            Map.entry("Y", "Market Data Request Reject"),
            Map.entry("AA", "Derivative Security List"),
            Map.entry("AE", "Trade Capture Report"),
            Map.entry("AP", "Registration Instructions"),
            Map.entry("BE", "User Request"),
            Map.entry("BF", "User Response")
    );

    private static final Map<String, String> TAG_NAMES = Map.ofEntries(
            Map.entry("8", "BeginString"),
            Map.entry("9", "BodyLength"),
            Map.entry("10", "CheckSum"),
            Map.entry("11", "ClOrdID"),
            Map.entry("14", "CumQty"),
            Map.entry("15", "Currency"),
            Map.entry("17", "ExecID"),
            Map.entry("20", "ExecTransType"),
            Map.entry("21", "HandlInst"),
            Map.entry("22", "SecurityIDSource"),
            Map.entry("31", "LastPx"),
            Map.entry("32", "LastQty"),
            Map.entry("34", "MsgSeqNum"),
            Map.entry("35", "MsgType"),
            Map.entry("37", "OrderID"),
            Map.entry("38", "OrderQty"),
            Map.entry("39", "OrdStatus"),
            Map.entry("40", "OrdType"),
            Map.entry("41", "OrigClOrdID"),
            Map.entry("44", "Price"),
            Map.entry("48", "SecurityID"),
            Map.entry("49", "SenderCompID"),
            Map.entry("50", "SenderSubID"),
            Map.entry("52", "SendingTime"),
            Map.entry("54", "Side"),
            Map.entry("55", "Symbol"),
            Map.entry("56", "TargetCompID"),
            Map.entry("57", "TargetSubID"),
            Map.entry("58", "Text"),
            Map.entry("59", "TimeInForce"),
            Map.entry("60", "TransactTime"),
            Map.entry("75", "TradeDate"),
            Map.entry("100", "ExDestination"),
            Map.entry("150", "ExecType"),
            Map.entry("151", "LeavesQty"),
            Map.entry("167", "SecurityType"),
            Map.entry("207", "SecurityExchange")
    );

    private static final Map<String, String> SIDE_VALUES = Map.of(
            "1", "Buy", "2", "Sell", "3", "Buy Minus", "4", "Sell Plus",
            "5", "Sell Short", "6", "Sell Short Exempt"
    );

    private static final Map<String, String> ORD_TYPE_VALUES = Map.of(
            "1", "Market", "2", "Limit", "3", "Stop", "4", "Stop Limit",
            "K", "Market If Touched", "P", "Pegged"
    );

    private static final Map<String, String> TIME_IN_FORCE_VALUES = Map.of(
            "0", "Day", "1", "GTC", "2", "At the Opening", "3", "IOC",
            "4", "FOK", "6", "Good Till Date"
    );

    public EdiDocument parse(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();

        String version = null, msgType = null, senderId = null, receiverId = null;
        String seqNum = null, sendingTime = null;
        int fieldCount = 0;

        // Detect delimiter: SOH (0x01) or pipe '|'
        String delim = content.contains("\001") ? "\001" : "\\|";
        String[] fields = content.split(delim);

        for (String field : fields) {
            if (field.isEmpty()) continue;
            fieldCount++;

            try {
                int eqPos = field.indexOf('=');
                if (eqPos < 0) {
                    warnings.add("Field " + fieldCount + ": no '=' found in '" + field + "'");
                    continue;
                }

                String tag = field.substring(0, eqPos);
                String value = field.substring(eqPos + 1);
                String tagName = TAG_NAMES.getOrDefault(tag, "Tag" + tag);

                Map<String, String> named = new LinkedHashMap<>();
                named.put("tag", tag);
                named.put("tagName", tagName);
                named.put("value", value);

                // Enrich specific tags with human-readable descriptions
                switch (tag) {
                    case "8" -> { version = value; }
                    case "35" -> {
                        msgType = value;
                        named.put("messageTypeName", MSG_TYPES.getOrDefault(value, "Unknown"));
                    }
                    case "49" -> { senderId = value; }
                    case "56" -> { receiverId = value; }
                    case "34" -> { seqNum = value; }
                    case "52" -> { sendingTime = value; }
                    case "54" -> {
                        named.put("sideDescription", SIDE_VALUES.getOrDefault(value, "Unknown"));
                        biz.put("side", SIDE_VALUES.getOrDefault(value, value));
                    }
                    case "40" -> {
                        named.put("orderTypeDescription", ORD_TYPE_VALUES.getOrDefault(value, "Unknown"));
                        biz.put("orderType", ORD_TYPE_VALUES.getOrDefault(value, value));
                    }
                    case "59" -> {
                        named.put("timeInForceDescription", TIME_IN_FORCE_VALUES.getOrDefault(value, "Unknown"));
                    }
                    case "55" -> biz.put("symbol", value);
                    case "38" -> biz.put("orderQty", value);
                    case "44" -> biz.put("price", value);
                    case "11" -> biz.put("clOrdID", value);
                    case "37" -> biz.put("orderID", value);
                    case "17" -> biz.put("execID", value);
                    case "150" -> biz.put("execType", value);
                    case "39" -> biz.put("ordStatus", value);
                    case "31" -> biz.put("lastPx", value);
                    case "32" -> biz.put("lastQty", value);
                    case "14" -> biz.put("cumQty", value);
                    case "151" -> biz.put("leavesQty", value);
                }

                // Store all tag values in businessData
                biz.put("tag_" + tag, value);

                segments.add(Segment.builder()
                        .id("Tag" + tag)
                        .elements(List.of(value))
                        .namedFields(named)
                        .build());
            } catch (Exception e) {
                errors.add("Field " + fieldCount + ": " + e.getMessage());
            }
        }

        String msgName = MSG_TYPES.getOrDefault(msgType != null ? msgType : "", "FIX Message");
        biz.put("fieldCount", fieldCount);

        DelimiterInfo delimiters = DelimiterInfo.builder()
                .elementSeparator(content.contains("\001") ? '\001' : '|')
                .build();

        return EdiDocument.builder()
                .sourceFormat("FIX")
                .documentType(msgType)
                .documentName(msgName)
                .version(version)
                .senderId(senderId)
                .receiverId(receiverId)
                .documentDate(sendingTime)
                .controlNumber(seqNum)
                .segments(segments)
                .rawContent(content)
                .businessData(biz)
                .delimiterInfo(delimiters)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .build();
    }
}
