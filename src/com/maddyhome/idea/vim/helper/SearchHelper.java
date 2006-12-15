package com.maddyhome.idea.vim.helper;

/*
 * IdeaVim - A Vim emulator plugin for IntelliJ Idea
 * Copyright (C) 2003-2005 Rick Maddy
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.maddyhome.idea.vim.common.TextRange;
import com.maddyhome.idea.vim.option.ListOption;
import com.maddyhome.idea.vim.option.OptionChangeEvent;
import com.maddyhome.idea.vim.option.OptionChangeListener;
import com.maddyhome.idea.vim.option.Options;

import java.util.List;

/**
 * Helper methods for searching text
 */
public class SearchHelper
{
    public static boolean anyNonWhitespace(Editor editor, int offset, int dir)
    {
        int start;
        int end;
        if (dir > 0)
        {
            start = offset + 1;
            end = EditorHelper.getLineEndForOffset(editor, offset);
        }
        else
        {
            start = EditorHelper.getLineStartForOffset(editor, offset);
            end = offset - 1;
        }

        CharSequence chars = EditorHelper.getDocumentChars(editor);
        for (int i = start; i <= end; i++)
        {
            if (!Character.isWhitespace(chars.charAt(i)))
            {
                return true;
            }
        }

        return false;
    }

    public static int findSection(Editor editor, char type, int dir, int count)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int line = EditorHelper.getCurrentLogicalLine(editor) + dir;
        int maxline = EditorHelper.getLineCount(editor);
        int res = -1;

        while (line > 0 && line < maxline && count > 0)
        {
            int offset = EditorHelper.getLineStartOffset(editor, line);
            char ch = chars.charAt(offset);
            if (ch == type || ch == '\u000C')
            {
                res = offset;
                count--;
            }

            line += dir;
        }

        if (res == -1)
        {
            res = dir < 0 ? 0 : chars.length() - 1;
        }

        return res;
    }

    public static int findUnmatchedBlock(Editor editor, char type, int count)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int loc = blockChars.indexOf(type);
        // What direction should we go now (-1 is backward, 1 is forward)
        int dir = loc % 2 == 0 ? -1 : 1;
        // Which character did we find and which should we now search for
        char match = blockChars.charAt(loc);
        char found = blockChars.charAt(loc - dir);

        return findBlockLocation(chars, found, match, dir, pos, count);
    }

    public static TextRange findBlockRange(Editor editor, char type, int count, boolean isOuter)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int start = editor.getSelectionModel().getSelectionStart();
        int end = editor.getSelectionModel().getSelectionEnd();
        if (start != end)
        {
            pos = Math.min(start, end);
        }

        int loc = blockChars.indexOf(type);
        char close = blockChars.charAt(loc + 1);

        int bstart = findBlockLocation(chars, close, type, -1, pos, count);
        if (bstart == -1)
        {
            return null;
        }

        int bend = findBlockLocation(chars, type, close, 1, bstart + 1, 1);

        if (!isOuter)
        {
            bstart++;
            if (chars.charAt(bstart) == '\n')
            {
                bstart++;
            }

            int o = EditorHelper.getLineStartForOffset(editor, bend);
            boolean allWhite = true;
            for (int i = o; i < bend; i++)
            {
                if (!Character.isWhitespace(chars.charAt(i)))
                {
                    allWhite = false;
                    break;
                }
            }

            if (allWhite)
            {
                bend = o - 2;
            }
            else
            {
                bend--;
            }
        }

        return new TextRange(bstart, bend);
    }

    /**
     * This looks on the current line, starting at the cursor postion for one of {, }, (, ), [, or ]. It then searches
     * forward or backward, as appropriate for the associated match pair. String in double quotes are skipped over.
     * Single characters in single quotes are skipped too.
     *
     * @param editor The editor to search in
     * @return The offset within the editor of the found character or -1 if no match was found or none of the characters
     *         were found on the remainder of the current line.
     */
    public static int findMatchingPairOnCurrentLine(Editor editor)
    {
        int line = EditorHelper.getCurrentLogicalLine(editor);
        int end = EditorHelper.getLineEndOffset(editor, line, true);
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int loc = -1;
        // Search the remainder of the current line for one of the candidate characters
        while (pos < end)
        {
            loc = getPairChars().indexOf(chars.charAt(pos));
            if (loc >= 0)
            {
                break;
            }

            pos++;
        }

        int res = -1;
        // If we found one ...
        if (loc >= 0)
        {
            // What direction should we go now (-1 is backward, 1 is forward)
            int dir = loc % 2 == 0 ? 1 : -1;
            // Which character did we find and which should we now search for
            char found = getPairChars().charAt(loc);
            char match = getPairChars().charAt(loc + dir);
            res = findBlockLocation(chars, found, match, dir, pos, 1);
        }

        return res;
    }

    private static int findBlockLocation(CharSequence chars, char found, char match, int dir, int pos, int cnt)
    {
        int res = -1;
        boolean inString = checkInString(chars, pos, true);
        boolean inChar = checkInString(chars, pos, false);
        int stack = 0;
        pos += dir;
        // Search to start or end of file, as appropriate
        while (pos >= 0 && pos < chars.length() && cnt > 0)
        {
            // If we found a match and we're not in a string...
            if (chars.charAt(pos) == match && !inString && !inChar)
            {
                // We found our match
                if (stack == 0)
                {
                    res = pos;
                    cnt--;
                }
                // Found the character but it "closes" a different pair
                else
                {
                    stack--;
                }
            }
            // We found another character like our original - belongs to another pair
            else if (chars.charAt(pos) == found && !inString && !inChar)
            {
                stack++;
            }
            // We found the start/end of a string
            else if (!inChar && chars.charAt(pos) == '"' && (pos == 0 || chars.charAt(pos - 1) != '\\'))
            {
                inString = !inString;
            }
            else if (!inString && chars.charAt(pos) == '\'' && (pos == 0 || chars.charAt(pos - 1) != '\\'))
            {
                inChar = !inChar;
            }
            // End of line - mark not in a string any more (in case we started in the middle of one
            else if (chars.charAt(pos) == '\n')
            {
                inString = false;
                inChar = false;
            }

            pos += dir;
        }

        return res;
    }

    private static boolean checkInString(CharSequence chars, int pos, boolean str)
    {
        int offset = pos;
        while (offset >= 0 && chars.charAt(offset) != '\n')
        {
            offset--;
        }

        boolean inString = false;
        boolean inChar = false;
        for (int i = offset; i < pos; i++)
        {
            if (!inChar && chars.charAt(i) == '"' && (i == 0 || chars.charAt(i - 1) != '\\'))
            {
                inString = !inString;
            }
            else if (!inString && chars.charAt(i) == '\'' && (i == 0 || chars.charAt(i - 1) != '\\'))
            {
                inChar = !inChar;
            }
        }

        return str ? inString : inChar;
    }

    public static int findNextCamelStart(Editor editor, int count)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int size = EditorHelper.getFileSize(editor);

        int found = 0;
        int step = count >= 0 ? 1 : -1;
        if (pos < 0 || pos >= size)
        {
            return pos;
        }

        int res = pos;
        pos += step;
        while (pos >= 0 && pos < size && found < Math.abs(count))
        {
            if (Character.isUpperCase(chars.charAt(pos)))
            {
                if ((pos == 0 || !Character.isUpperCase(chars.charAt(pos - 1))) ||
                    (pos == size - 1 || Character.isLowerCase(chars.charAt(pos + 1))))
                {
                    res = pos;
                    found++;
                }
            }
            else if (Character.isLowerCase(chars.charAt(pos)))
            {
                if (pos == 0 || !Character.isLetter(chars.charAt(pos - 1)))
                {
                    res = pos;
                    found++;
                }
            }
            else if (Character.isDigit(chars.charAt(pos)))
            {
                if (pos == 0 || !Character.isDigit(chars.charAt(pos - 1)))
                {
                    res = pos;
                    found++;
                }
            }

            pos += step;
        }

        if (found < Math.abs(count))
        {
            res = -1;
        }

        return res;
    }

    public static int findNextCamelEnd(Editor editor, int count)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int size = EditorHelper.getFileSize(editor);

        int found = 0;
        int step = count >= 0 ? 1 : -1;
        if (pos < 0 || pos >= size)
        {
            return pos;
        }

        int res = pos;
        pos += step;
        while (pos >= 0 && pos < size && found < Math.abs(count))
        {
            if (Character.isUpperCase(chars.charAt(pos)))
            {
                if (pos == size - 1 || !Character.isLetter(chars.charAt(pos + 1)) ||
                    (Character.isUpperCase(chars.charAt(pos + 1)) && pos <= size - 2 && Character.isLowerCase(chars.charAt(pos + 2))))
                {
                    res = pos;
                    found++;
                }
            }
            else if (Character.isLowerCase(chars.charAt(pos)))
            {
                if (pos == size - 1 || !Character.isLowerCase(chars.charAt(pos + 1)))
                {
                    res = pos;
                    found++;
                }
            }
            else if (Character.isDigit(chars.charAt(pos)))
            {
                if (pos == size - 1 || !Character.isDigit(chars.charAt(pos + 1)))
                {
                    res = pos;
                    found++;
                }
            }

            pos += step;
        }

        if (found < Math.abs(count))
        {
            res = -1;
        }

        return res;
    }

    /**
     * This finds the offset to the start of the next/previous word/WORD.
     *
     * @param editor The editor to find the words in
     * @param count The number of words to skip. Negative for backward searches
     * @param skipPunc If true then find WORD, if false then find word
     * @return The offset of the match
     */
    public static int findNextWord(Editor editor, int count, boolean skipPunc)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int size = EditorHelper.getFileSize(editor);

        return findNextWord(chars, pos, size, count, skipPunc, false);
    }

    public static int findNextWord(CharSequence chars, int pos, int size, int count, boolean skipPunc, boolean spaceWords)
    {
        int step = count >= 0 ? 1 : -1;
        count = Math.abs(count);

        int res = pos;
        for (int i = 0; i < count; i++)
        {
            res = findNextWordOne(chars, res, size, step, skipPunc, spaceWords);
            if (res == pos || res == 0 || res == size - 1)
            {
                break;
            }
        }

        return res;
    }

    private static int findNextWordOne(CharSequence chars, int pos, int size, int step, boolean skipPunc, boolean spaceWords)
    {
        boolean found = false;
        // For back searches, skip any current whitespace so we start at the end of a word
        if (step < 0 && pos > 0)
        {
            if (CharacterHelper.charType(chars.charAt(pos - 1), skipPunc) == CharacterHelper.TYPE_SPACE && !spaceWords)
            {
                pos = skipSpace(chars, pos - 1, step, size) + 1;
            }
            if (CharacterHelper.charType(chars.charAt(pos), skipPunc) != CharacterHelper.charType(chars.charAt(pos - 1), skipPunc))
            {
                pos += step;
            }
        }
        int res = pos;
        if (pos < 0 || pos >= size)
        {
            return pos;
        }

        int type = CharacterHelper.charType(chars.charAt(pos), skipPunc);
        if (type == CharacterHelper.TYPE_SPACE && step < 0 && pos > 0 && !spaceWords)
        {
            type = CharacterHelper.charType(chars.charAt(pos - 1), skipPunc);
        }

        pos += step;
        while (pos >= 0 && pos < size && !found)
        {
            int newType = CharacterHelper.charType(chars.charAt(pos), skipPunc);
            if (newType != type)
            {
                if (newType == CharacterHelper.TYPE_SPACE && step >= 0 && !spaceWords)
                {
                    pos = skipSpace(chars, pos, step, size);
                    res = pos;
                }
                else if (step < 0)
                {
                    res = pos + 1;
                }
                else
                {
                    res = pos;
                }

                type = CharacterHelper.charType(chars.charAt(res), skipPunc);
                found = true;
            }

            pos += step;
        }

        if (found)
        {
            if (res < 0) //(pos <= 0)
            {
                res = 0;
            }
            else if (res >= size) //(pos >= size)
            {
                res = size - 1;
            }
        }
        else if (pos <= 0)
        {
            res = 0;
        }

        return res;
    }

    public static TextRange findNumberUnderCursor(Editor editor, boolean alpha, boolean hex, boolean octal)
    {
        int lline = EditorHelper.getCurrentLogicalLine(editor);
        String text = EditorHelper.getLineText(editor, lline).toLowerCase();
        int offset = EditorHelper.getLineStartOffset(editor, lline);
        int pos = editor.getCaretModel().getOffset() - offset;

        logger.debug("lline=" + lline);
        logger.debug("text=" + text);
        logger.debug("offset=" + offset);
        logger.debug("pos=" + pos);

        while (true)
        {
            // Skip over current whitespace if any
            while (pos < text.length() && !isNumberChar(text.charAt(pos), alpha, hex, octal, true))
            {
                pos++;
            }

            logger.debug("pos=" + pos);
            if (pos >= text.length())
            {
                logger.debug("no number char on line");
                return null;
            }

            boolean isHexChar = "abcdefABCDEF".indexOf(text.charAt(pos)) >= 0;

            if (hex)
            {
                if (text.charAt(pos) == '0' && pos < text.length() - 1 && "xX".indexOf(text.charAt(pos + 1)) >= 0)
                {
                    pos += 2;
                }
                else if ("xX".indexOf(text.charAt(pos)) >= 0 && pos > 0 && text.charAt(pos - 1) == '0')
                {
                    pos++;
                }

                logger.debug("checking hex");
                int end = pos;
                for (; end < text.length(); end++)
                {
                    if (!isNumberChar(text.charAt(end), false, true, false, false))
                    {
                        end--;
                        break;
                    }
                }

                int start = pos;
                for (; start >= 0; start--)
                {
                    if (!isNumberChar(text.charAt(start), false, true, false, false))
                    {
                        start++;
                        break;
                    }
                }
                if (start == -1) start = 0;

                if (start >= 2 && text.substring(start - 2, start).toLowerCase().equals("0x"))
                {
                    logger.debug("found hex");
                    return new TextRange(start - 2 + offset, end + offset + 1);
                }

                if (!isHexChar || alpha)
                {
                    break;
                }
                else
                {
                    pos++;
                }
            }
            else
            {
                break;
            }
        }

        if (octal)
        {
            logger.debug("checking octal");
            int end = pos;
            for (; end < text.length(); end++)
            {
                if (!isNumberChar(text.charAt(end), false, false, true, false))
                {
                    end--;
                    break;
                }
            }

            int start = pos;
            for (; start >= 0; start--)
            {
                if (!isNumberChar(text.charAt(start), false, false, true, false))
                {
                    start++;
                    break;
                }
            }
            if (start == -1) start = 0;

            if (text.charAt(start) == '0' && end > start)
            {
                logger.debug("found octal");
                return new TextRange(start + offset, end + offset + 1);
            }
        }

        if (alpha)
        {
            logger.debug("checking alpha for " + text.charAt(pos));
            if (isNumberChar(text.charAt(pos), true, false, false, false))
            {
                logger.debug("found alpha at " + pos);
                return new TextRange(pos + offset, pos + offset + 1);
            }
        }

        int end = pos;
        for (; end < text.length(); end++)
        {
            if (!isNumberChar(text.charAt(end), false, false, false, true))
            {
                end--;
                break;
            }
        }

        int start = pos;
        for (; start >= 0; start--)
        {
            if (!isNumberChar(text.charAt(start), false, false, false, true))
            {
                start++;
                break;
            }
        }
        if (start == -1) start = 0;

        if (start > 0 && text.charAt(start - 1) == '-')
        {
            start--;
        }

        return new TextRange(start + offset, end + offset + 1);
    }

    private static boolean isNumberChar(char ch, boolean alpha, boolean hex, boolean octal, boolean decimal)
    {
        if (alpha && ((ch >='a' && ch <='z') || (ch >='A' && ch <= 'Z')))
        {
            return true;
        }
        else if (octal && (ch >= '0' && ch <= '7'))
        {
            return true;
        }
        else if (hex && ((ch >= '0' && ch <= '9') || "abcdefABCDEF".indexOf(ch) >= 0))
        {
            return true;
        }
        else if (decimal && (ch >= '0' && ch <= '9'))
        {
            return true;
        }

        return false;
    }

    /**
     * Find the word under the cursor or the next word to the right of the cursor on the current line.
     *
     * @param editor The editor to find the word in
     * @return The text range of the found word or null if there is no word under/after the cursor on the line
     */
    public static TextRange findWordUnderCursor(Editor editor)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int stop = EditorHelper.getLineEndOffset(editor, EditorHelper.getCurrentLogicalLine(editor), true);

        int pos = editor.getCaretModel().getOffset();
        int start = pos;
        int[] types = new int[]{CharacterHelper.TYPE_CHAR, CharacterHelper.TYPE_PUNC};
        for (int i = 0; i < 2; i++)
        {
            start = pos;
            int type = CharacterHelper.charType(chars.charAt(start), false);
            if (type == types[i])
            {
                // Search back for start of word
                while (start > 0 && CharacterHelper.charType(chars.charAt(start - 1), false) == types[i])
                {
                    start--;
                }
            }
            else
            {
                // Search forward for start of word
                while (start < stop && CharacterHelper.charType(chars.charAt(start), false) != types[i])
                {
                    start++;
                }
            }

            if (start != stop)
            {
                break;
            }
        }

        if (start == stop)
        {
            return null;
        }

        int end = start;
        // Special case 1 character words because 'findNextWordEnd' returns one to many chars
        if (start < stop && CharacterHelper.charType(chars.charAt(start + 1), false) != CharacterHelper.TYPE_CHAR)
        {
            end = start + 1;
        }
        else
        {
            end = findNextWordEnd(chars, start, stop, 1, false, false, false) + 1;
        }

        return new TextRange(start, end);
    }

    public static TextRange findWordUnderCursor(Editor editor, int count, int dir, boolean isOuter, boolean isBig, boolean hasSelection)
    {
        logger.debug("count=" + count);
        logger.debug("dir=" + dir);
        logger.debug("isOuter=" + isOuter);
        logger.debug("isBig=" + isBig);
        logger.debug("hasSelection=" + hasSelection);

        CharSequence chars = EditorHelper.getDocumentChars(editor);
        //int min = EditorHelper.getLineStartOffset(editor, EditorHelper.getCurrentLogicalLine(editor));
        //int max = EditorHelper.getLineEndOffset(editor, EditorHelper.getCurrentLogicalLine(editor), true);
        int min = 0;
        int max = EditorHelper.getFileSize(editor);

        logger.debug("min=" + min);
        logger.debug("max=" + max);

        int pos = editor.getCaretModel().getOffset();
        boolean startSpace = CharacterHelper.charType(chars.charAt(pos), isBig) == CharacterHelper.TYPE_SPACE;
        // Find word start
        boolean onWordStart = pos == min ||
            CharacterHelper.charType(chars.charAt(pos - 1), isBig) != CharacterHelper.charType(chars.charAt(pos), isBig);
        int start = pos;

        logger.debug("pos=" + pos);
        logger.debug("onWordStart=" + onWordStart);

        if ((!onWordStart && !(startSpace && isOuter)) || hasSelection || (count > 1 && dir == -1))
        {
            if (dir == 1)
            {
                start = findNextWord(chars, pos, max, -1, isBig, !isOuter);
            }
            else
            {
                start = findNextWord(chars, pos, max, -(count - (onWordStart && !hasSelection ? 1 : 0)), isBig, !isOuter);
            }

            start = EditorHelper.normalizeOffset(editor, start, false);
        }

        logger.debug("start=" + start);

        // Find word end
        boolean onWordEnd = pos == max ||
            CharacterHelper.charType(chars.charAt(pos + 1), isBig) != CharacterHelper.charType(chars.charAt(pos), isBig);

        logger.debug("onWordEnd=" + onWordEnd);

        int end = pos;
        if (!onWordEnd || hasSelection || (count > 1 && dir == 1) || (startSpace && isOuter))
        {
            if (dir == 1)
            {
                end = findNextWordEnd(chars, pos, max, count -
                    (onWordEnd && !hasSelection && (!(startSpace && isOuter) || (startSpace && !isOuter)) ? 1 : 0),
                    isBig, true, !isOuter);
            }
            else
            {
                end = findNextWordEnd(chars, pos, max, 1, isBig, true, !isOuter);
            }
        }

        logger.debug("end=" + end);

        boolean goBack = (startSpace && !hasSelection) || (!startSpace && hasSelection && !onWordStart);
        if (dir == 1 && isOuter)
        {
            int firstEnd = end;
            if (count > 1)
            {
                firstEnd = findNextWordEnd(chars, pos, max, 1, isBig, true, false);
            }
            if (firstEnd < max)
            {
                if (CharacterHelper.charType(chars.charAt(firstEnd + 1), false) != CharacterHelper.TYPE_SPACE)
                {
                    goBack = true;
                }
            }
        }
        if (dir == -1 && isOuter && startSpace)
        {
            if (pos > min)
            {
                if (CharacterHelper.charType(chars.charAt(pos - 1), false) != CharacterHelper.TYPE_SPACE)
                {
                    goBack = true;
                }
            }
        }

        boolean goForward = (dir == 1 && isOuter && ((!startSpace && !onWordEnd) || (startSpace && onWordEnd && hasSelection)));
        if (!goForward && dir == 1 && isOuter)
        {
            int firstEnd = end;
            if (count > 1)
            {
                firstEnd = findNextWordEnd(chars, pos, max, 1, isBig, true, false);
            }
            if (firstEnd < max)
            {
                if (CharacterHelper.charType(chars.charAt(firstEnd + 1), false) != CharacterHelper.TYPE_SPACE)
                {
                    goForward = true;
                }
            }
        }
        if (!goForward && dir == 1 && isOuter && !startSpace && !hasSelection)
        {
            if (end < max)
            {
                if (CharacterHelper.charType(chars.charAt(end + 1), !isBig) != CharacterHelper.charType(chars.charAt(end), !isBig))
                {
                    goForward = true;
                }
            }
        }

        logger.debug("goBack=" + goBack);
        logger.debug("goForward=" + goForward);

        if (goForward)
        {
            while (end < max && CharacterHelper.charType(chars.charAt(end + 1), false) == CharacterHelper.TYPE_SPACE)
            {
                end++;
            }
        }
        if (goBack)
        {
            while (start > min && CharacterHelper.charType(chars.charAt(start - 1), false) == CharacterHelper.TYPE_SPACE)
            {
                start--;
            }
        }

        logger.debug("start=" + start);
        logger.debug("end=" + end);

        return new TextRange(start, end);
    }

    /**
     * This finds the offset to the end of the next/previous word/WORD.
     *
     * @param editor The editor to search in
     * @param count The number of words to skip. Negative for backward searches
     * @param skipPunc If true then find WORD, if false then find word
     * @return The offset of match
     */
    public static int findNextWordEnd(Editor editor, int count, boolean skipPunc, boolean stayEnd)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int pos = editor.getCaretModel().getOffset();
        int size = EditorHelper.getFileSize(editor);

        return findNextWordEnd(chars, pos, size, count, skipPunc, stayEnd, false);
    }

    public static int findNextWordEnd(CharSequence chars, int pos, int size, int count, boolean skipPunc, boolean stayEnd,
        boolean spaceWords)
    {
        int step = count >= 0 ? 1 : -1;
        count = Math.abs(count);

        int res = pos;
        for (int i = 0; i < count; i++)
        {
            res = findNextWordEndOne(chars, res, size, step, skipPunc, stayEnd, spaceWords);
            if (res == pos || res == 0 || res == size - 1)
            {
                break;
            }
        }

        return res;
    }

    private static int findNextWordEndOne(CharSequence chars, int pos, int size, int step, boolean skipPunc, boolean stayEnd,
        boolean spaceWords)
    {
        boolean found = false;
        // For forward searches, skip any current whitespace so we start at the start of a word
        if (step > 0 && pos < size - 1)
        {
            /*
            if (CharacterHelper.charType(chars[pos + step], false) == CharacterHelper.TYPE_SPACE)
            {
                if (!stayEnd)
                {
                    pos += step;
                }
                pos = skipSpace(chars, pos, step, size);
            }
            */
            if (CharacterHelper.charType(chars.charAt(pos + 1), skipPunc) == CharacterHelper.TYPE_SPACE && !spaceWords)
            {
                pos = skipSpace(chars, pos + 1, step, size) - 1;
            }
            if (CharacterHelper.charType(chars.charAt(pos), skipPunc) != CharacterHelper.charType(chars.charAt(pos + 1), skipPunc))
            {
                pos += step;
            }
        }
        int res = pos;
        if (pos < 0 || pos >= size)
        {
            return pos;
        }
        int type = CharacterHelper.charType(chars.charAt(pos), skipPunc);
        if (type == CharacterHelper.TYPE_SPACE && step >= 0 && pos < size - 1 && !spaceWords)
        {
            type = CharacterHelper.charType(chars.charAt(pos + 1), skipPunc);
        }

        pos += step;
        while (pos >= 0 && pos < size && !found)
        {
            int newType = CharacterHelper.charType(chars.charAt(pos), skipPunc);
            if (newType != type)
            {
                if (step >= 0)
                {
                    res = pos - 1;
                }
                else if (newType == CharacterHelper.TYPE_SPACE && step < 0 && !spaceWords)
                {
                    pos = skipSpace(chars, pos, step, size);
                    res = pos;
                }
                else
                {
                    res = pos;
                }

                found = true;
            }

            pos += step;
        }

        if (found)
        {
            if (res < 0) //(pos <= 0)
            {
                res = 0;
            }
            else if (res >= size) //(pos >= size)
            {
                res = size - 1;
            }
        }
        else if (pos == size)
        {
            res = size - 1;
        }

        return res;
    }

    /**
     * This skips whitespace starting with the supplied position.
     *
     * @param chars The text as a character array
     * @param offset The starting position
     * @param step The direction to move
     * @param size The size of the document
     * @return The new position. This will be the first non-whitespace character found
     */
    public static int skipSpace(CharSequence chars, int offset, int step, int size)
    {
        while (offset >= 0 && offset < size)
        {
            if (CharacterHelper.charType(chars.charAt(offset), false) != CharacterHelper.TYPE_SPACE)
            {
                break;
            }

            offset += step;
        }

        return offset;
    }

    /**
     * This locates the position with the document of the count'th occurence of ch on the current line
     *
     * @param editor The editor to search in
     * @param count The number of occurences of ch to locate. Negative for backward searches
     * @param ch The character on the line to find
     * @return The document offset of the matching character match, -1
     */
    public static int findNextCharacterOnLine(Editor editor, int count, char ch)
    {
        int line = EditorHelper.getCurrentLogicalLine(editor);
        int start = EditorHelper.getLineStartOffset(editor, line);
        int end = EditorHelper.getLineEndOffset(editor, line, true);
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int found = 0;
        int step = count >= 0 ? 1 : -1;
        int pos = editor.getCaretModel().getOffset() + step;
        while (pos >= start && pos < end && pos >= 0 && pos < chars.length())
        {
            if (chars.charAt(pos) == ch)
            {
                found++;
                if (found == Math.abs(count))
                {
                    break;
                }
            }
            pos += step;
        }

        if (found == Math.abs(count))
        {
            return pos;
        }
        else
        {
            return -1;
        }
    }

    public static int findNextParagraph(Editor editor, int count, boolean allowBlanks)
    {
        int line = findNextParagraphLine(editor, count, allowBlanks);

        return EditorHelper.getLineStartOffset(editor, line);
    }

    private static int findNextParagraphLine(Editor editor, int count, boolean allowBlanks)
    {
        CharSequence chars = EditorHelper.getDocumentChars(editor);
        int dir = count > 0 ? 1 : -1;
        count = Math.abs(count);
        int line = EditorHelper.getCurrentLogicalLine(editor);
        int maxline = EditorHelper.getLineCount(editor);
        int res = -1;

        line = skipEmptyLines(editor, line, dir, allowBlanks);
        while (line >= 0 && line < maxline && count > 0)
        {
            if (EditorHelper.isLineEmpty(editor, line, allowBlanks))
            {
                res = line;
                count--;
                if (count > 0)
                {
                    line = skipEmptyLines(editor, line, dir, allowBlanks);
                }
            }

            line += dir;
        }

        if (res == -1 || count > 0)
        {
            //res = dir < 0 ? 0 : chars.length() - 1;
            res = dir < 0 ? 0 : maxline - 1;
        }

        return res;
    }

    private static int skipEmptyLines(Editor editor, int line, int dir, boolean allowBlanks)
    {
        int maxline = EditorHelper.getLineCount(editor);
        while (line >= 0 && line < maxline)
        {
            if (!EditorHelper.isLineEmpty(editor, line, allowBlanks))
            {
                return line;
            }

            line += dir;
        }

        return line;
    }

    public static TextRange findParagraphRange(Editor editor, int count, boolean isOuter)
    {
        int line = EditorHelper.getCurrentLogicalLine(editor);
        logger.debug("starting on line " + line);
        int sline = line;
        int eline = line;
        if (EditorHelper.isLineEmpty(editor, sline, true))
        {
            logger.debug("starting on an empty line");
            sline = skipEmptyLines(editor, sline, -1, true);
            if (!EditorHelper.isLineEmpty(editor, sline, true))
            {
                sline++;
            }

            if (isOuter)
            {
                eline = findNextParagraphLine(editor, count, true);
                if (EditorHelper.isLineEmpty(editor, eline, true))
                {
                    eline--;
                }
            }
            else
            {
                eline = skipEmptyLines(editor, sline, 1, false);
                if (!EditorHelper.isLineEmpty(editor, eline, true))
                {
                    eline--;
                }
            }
        }
        else
        {
            logger.debug("starting on unempty line");
            sline = findNextParagraphLine(editor, -count, true);
            logger.debug("sline=" + sline);
            if (EditorHelper.isLineEmpty(editor, sline, true))
            {
                sline++;
                logger.debug("empty: sline=" + sline);
            }

            eline = findNextParagraphLine(editor, count, true);
            logger.debug("eline=" + eline);
            if (!EditorHelper.isLineEmpty(editor, eline, true))
            {
                logger.debug("eline not empty");
                if (isOuter)
                {
                    sline = findNextParagraphLine(editor, -count, true);
                    logger.debug("outer: sline=" + sline);
                    sline = skipEmptyLines(editor, sline, -1, true);
                    logger.debug("outer: sline=" + sline);
                    if (!EditorHelper.isLineEmpty(editor, sline, true))
                    {
                        sline++;
                        logger.debug("unempty: sline=" + sline);
                    }
                }
            }
            else
            {
                logger.debug("eline empty");
                if (isOuter)
                {
                    eline = skipEmptyLines(editor, eline, 1, true);
                    logger.debug("outer: eline=" + eline);
                    if (!EditorHelper.isLineEmpty(editor, eline, true))
                    {
                        eline--;
                        logger.debug("unempty: eline=" + eline);
                    }
                }
                else
                {
                    logger.debug("inner");
                    if (EditorHelper.isLineEmpty(editor, eline, true))
                    {
                        eline--;
                        logger.debug("empty: eline=" + eline);
                    }
                }
            }
        }

        logger.debug("final sline=" + sline);
        logger.debug("final eline=" + sline);
        int start = EditorHelper.getLineStartOffset(editor, sline);
        int end = EditorHelper.getLineStartOffset(editor, eline);

        return new TextRange(start, end);
    }

    private static String getPairChars()
    {
        if (pairsChars == null)
        {
            ListOption lo = (ListOption)Options.getInstance().getOption("matchpairs");
            pairsChars = parseOption(lo);

            lo.addOptionChangeListener(new OptionChangeListener()
            {
                public void valueChange(OptionChangeEvent event)
                {
                    pairsChars = parseOption((ListOption)event.getOption());
                }
            });
        }

        return pairsChars;
    }

    private static String parseOption(ListOption option)
    {
        List vals = option.values();
        StringBuffer res = new StringBuffer();
        for (int i = 0; i < vals.size(); i++)
        {
            String s = (String)vals.get(i);
            if (s.length() == 3)
            {
                res.append(s.charAt(0)).append(s.charAt(2));
            }
        }

        return res.toString();
    }

    private static String pairsChars = null;
    private static String blockChars = "{}()[]<>";

    private static Logger logger = Logger.getInstance(SearchHelper.class.getName());
}
