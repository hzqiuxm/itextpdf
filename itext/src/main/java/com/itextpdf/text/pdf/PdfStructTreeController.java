package com.itextpdf.text.pdf;

import com.itextpdf.text.error_messages.MessageLocalization;

import java.io.IOException;
import java.util.Map;

public class PdfStructTreeController {

    private PdfDictionary structTreeRoot;
    private PdfCopy writer;
    private PdfStructureTreeRoot structureTreeRoot;
    private PdfDictionary parentTree;
    protected PdfReader reader;
    private PdfDictionary roleMap = null;
    private PdfDictionary sourceRoleMap = null;
    private PdfDictionary sourceClassMap = null;
//    private HashSet<Integer> openedDocuments = new HashSet<Integer>();

    public static enum returnType {BELOW, FOUND, ABOVE, NOTFOUND};

    public static final PdfName[] standardTypes = {PdfName.P, PdfName.H, PdfName.H1, PdfName.H2, PdfName.H3, PdfName.H4,
            PdfName.H5, PdfName.H6, PdfName.L, PdfName.LBL, PdfName.LI, PdfName.LBODY, PdfName.TABLE, PdfName.TABLEROW,
            PdfName.TH, PdfName.TD, PdfName.THEAD, PdfName.TBODY, PdfName.TFOOT, PdfName.SPAN, PdfName.QUOTE, PdfName.NOTE,
            PdfName.REFERENCE, PdfName.BIBENTRY, PdfName.CODE, PdfName.LINK, PdfName.ANNOT, PdfName.RUBY, PdfName.WARICHU};

    protected PdfStructTreeController(PdfReader reader, PdfCopy writer) throws BadPdfFormatException {
        if (!writer.isTagged())
            throw new BadPdfFormatException(MessageLocalization.getComposedMessage("no.structtreeroot.found"));
        this.writer = writer;
        structureTreeRoot = writer.getStructureTreeRoot();
        structureTreeRoot.put(PdfName.PARENTTREE, new PdfDictionary(PdfName.STRUCTELEM));
        setReader(reader);
    }

    protected void setReader(PdfReader reader) throws BadPdfFormatException {
        this.reader = reader;
        PdfObject obj = reader.getCatalog().get(PdfName.STRUCTTREEROOT);
        obj = getDirectObject(obj);
        if ((obj == null) || (!obj.isDictionary()))
            throw new BadPdfFormatException(MessageLocalization.getComposedMessage("no.structtreeroot.found"));
        structTreeRoot = (PdfDictionary) obj;
        obj = PdfStructTreeController.getDirectObject(structTreeRoot.get(PdfName.PARENTTREE));
        if (!obj.isDictionary())
            throw new BadPdfFormatException(MessageLocalization.getComposedMessage("the.document.does.not.contain.parenttree"));
        parentTree = (PdfDictionary) obj;
        sourceRoleMap = null;
        sourceClassMap = null;
    }

    public static PdfObject getDirectObject(PdfObject object) {
        if (object == null)
            return null;
        while (object.isIndirect())
            object = PdfReader.getPdfObjectRelease(object);
        return object;
    }

    public void copyStructTreeForPage(PdfNumber sourceArrayNumber, int newArrayNumber) throws BadPdfFormatException, IOException {
//        int documentHash = getDocumentHash(reader);
//        if (!openedDocuments.contains(documentHash)) {
//            openedDocuments.add(documentHash);
//
//        }
        if (copyPageMarks(parentTree, sourceArrayNumber, newArrayNumber) == returnType.NOTFOUND) {
            throw new BadPdfFormatException(MessageLocalization.getComposedMessage("invalid.structparent"));
        }
    }

    private returnType copyPageMarks(PdfDictionary parentTree, PdfNumber arrayNumber, int newArrayNumber) throws BadPdfFormatException, IOException {
        PdfArray pages = (PdfArray) getDirectObject(parentTree.get(PdfName.NUMS));
        if (pages == null) {
            PdfArray kids = (PdfArray) getDirectObject(parentTree.get(PdfName.KIDS));
            if (kids == null)
                return returnType.NOTFOUND;
            int cur = kids.size() / 2;
            int begin = 0;
            while (true) {
                PdfDictionary kidTree = (PdfDictionary) getDirectObject(kids.getPdfObject(cur + begin));
                switch (copyPageMarks(kidTree, arrayNumber, newArrayNumber)) {
                    case FOUND:
                        return returnType.FOUND;
                    case ABOVE:
                        begin += cur;
                        cur /= 2;
                        if (cur == 0)
                            cur = 1;
                        if (cur + begin == kids.size())
                            return returnType.ABOVE;
                        break;
                    case BELOW:
                        if (cur + begin == 0)
                            return returnType.BELOW;
                        if (cur == 0)
                            return returnType.NOTFOUND;
                        cur /= 2;
                        break;
                    default:
                        return returnType.NOTFOUND;
                }
            }
        } else {
            if (pages.size() == 0) return returnType.NOTFOUND;
            return findAndCopyMarks(pages, arrayNumber.intValue(), newArrayNumber);
        }
    }

    private returnType findAndCopyMarks(PdfArray pages, int arrayNumber, int newArrayNumber) throws BadPdfFormatException, IOException {
        if (pages.getAsNumber(0).intValue() > arrayNumber)
            return returnType.BELOW;
        if (pages.getAsNumber(pages.size() - 2).intValue() < arrayNumber)
            return returnType.ABOVE;
        int cur = pages.size() / 4;
        int begin = 0;
        int curNumber;
        while (true) {
            curNumber = pages.getAsNumber((begin + cur) * 2).intValue();
            if (curNumber == arrayNumber) {
                PdfObject obj = pages.getPdfObject((begin + cur) * 2 + 1);
                while (obj.isIndirect()) obj = PdfReader.getPdfObjectRelease(obj);
                //invalid Nums
                if (!obj.isArray()) return returnType.NOTFOUND;

                PdfObject firstNotNullKid = null;
                for (PdfObject numObj: (PdfArray)obj){
                    if (numObj.isNull()) continue;
                    PdfObject res = writer.copyObject(numObj, true, false);
                    if (firstNotNullKid == null) firstNotNullKid = res;
                    structureTreeRoot.setPageMark(newArrayNumber, (PdfIndirectReference) res);
                }
                //Add kid to structureTreeRoot from structTreeRoot
                PdfObject structKids = structTreeRoot.get(PdfName.K);
                if (structKids == null || (!structKids.isArray() && !structKids.isIndirect())) {
                    // incorrect syntax of tags
                    addKid(structureTreeRoot, firstNotNullKid);
                } else {
                    if (structKids.isIndirect()) {
                        addKid(structKids);
                    } else { //structKids.isArray()
                        for (PdfObject kid: (PdfArray)structKids)
                            addKid(kid);
                    }
                }
                return returnType.FOUND;
            }
            if (curNumber < arrayNumber) {
                begin += cur;
                cur /= 2;
                if (cur == 0)
                    cur = 1;
                if (cur + begin == pages.size())
                    return returnType.NOTFOUND;
                continue;
            }
            if (cur + begin == 0)
                return returnType.BELOW;
            if (cur == 0)
                return returnType.NOTFOUND;
            cur /= 2;
        }
    }

    private void addKid(PdfObject obj) throws IOException, BadPdfFormatException {
        if (!obj.isIndirect()) return;
        PRIndirectReference currRef = (PRIndirectReference)obj;
        PdfCopy.RefKey key =  new PdfCopy.RefKey(currRef);
        if (!writer.indirects.containsKey(key)) {
            writer.copyIndirect(currRef, true, false);
        }
        PdfIndirectReference newKid = writer.indirects.get(key).getRef();

        if (writer.updateRootKids) {
            addKid(structureTreeRoot, newKid);
        }
    }

    private static PdfArray getDirectArray(PdfArray in) {
        PdfArray out = new PdfArray();
        for (int i = 0; i < in.size(); ++i) {
            PdfObject value = getDirectObject(in.getPdfObject(i));
            if (value == null)
                continue;
            if (value.isArray()) {
                out.add(getDirectArray((PdfArray) value));
            } else if (value.isDictionary()) {
                out.add(getDirectDict((PdfDictionary) value));
            } else {
                out.add(value);
            }
        }
        return out;
    }

    private static PdfDictionary getDirectDict(PdfDictionary in) {
        PdfDictionary out = new PdfDictionary();
        for (Map.Entry<PdfName, PdfObject> entry : in.hashMap.entrySet()) {
            PdfObject value = getDirectObject(entry.getValue());
            if (value == null)
                continue;
            if (value.isArray()) {
                out.put(entry.getKey(), getDirectArray((PdfArray) value));
            } else if (value.isDictionary()) {
                out.put(entry.getKey(), getDirectDict((PdfDictionary) value));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    public static boolean compareObjects(PdfObject value1, PdfObject value2) {
        value2 = getDirectObject(value2);
        if (value2 == null)
            return false;
        if (value1.type() != value2.type())
            return false;

        if (value1.isBoolean()) {
            if (value1 == value2)
                return true;
            if (value2 instanceof PdfBoolean) {
                return ((PdfBoolean) value1).booleanValue() == ((PdfBoolean) value2).booleanValue();
            }
            return false;
        } else if (value1.isName()) {
            return value1.equals(value2);
        } else if (value1.isNumber()) {
            if (value1 == value2)
                return true;
            if (value2 instanceof PdfNumber) {
                return ((PdfNumber) value1).doubleValue() == ((PdfNumber) value2).doubleValue();
            }
            return false;
        } else if (value1.isNull()) {
            if (value1 == value2)
                return true;
            if (value2 instanceof PdfNull)
                return true;
            return false;
        } else if (value1.isString()) {
            if (value1 == value2)
                return true;
            if (value2 instanceof PdfString) {
                return ((((PdfString) value2).value == null && ((PdfString) value1).value == null)
                        || (((PdfString) value1).value != null && ((PdfString) value1).value.equals(((PdfString) value2).value)));
            }
            return false;
        }
        if (value1.isArray()) {
            PdfArray array1 = (PdfArray) value1;
            PdfArray array2 = (PdfArray) value2;
            if (array1.size() != array2.size())
                return false;
            for (int i = 0; i < array1.size(); ++i)
                if (!compareObjects(array1.getPdfObject(i), array2.getPdfObject(i)))
                    return false;
            return true;
        }
        if (value1.isDictionary()) {
            PdfDictionary first = (PdfDictionary) value1;
            PdfDictionary second = (PdfDictionary) value2;
            if (first.size() != second.size())
                return false;
            for (PdfName name : first.hashMap.keySet()) {
                if (!compareObjects(first.get(name), second.get(name)))
                    return false;
            }
            return true;
        }
        return false;
    }

    protected void addClass(PdfObject object) throws BadPdfFormatException {
        object = getDirectObject(object);
        if (object.isDictionary()) {
            PdfObject curClass = ((PdfDictionary) object).get(PdfName.C);
            if (curClass == null)
                return;
            if (curClass.isArray()) {
                PdfArray array = (PdfArray) curClass;
                for (int i = 0; i < array.size(); ++i) {
                    addClass(array.getPdfObject(i));
                }
            } else if (curClass.isName())
                addClass(curClass);
        } else if (object.isName()) {
            PdfName name = (PdfName) object;
            if (sourceClassMap == null) {
                object = getDirectObject(structTreeRoot.get(PdfName.CLASSMAP));
                if (object == null || !object.isDictionary()) {
                    return;
                }
                sourceClassMap = (PdfDictionary) object;
            }
            object = getDirectObject(sourceClassMap.get(name));
            if (object == null) {
                return;
            }
            PdfObject put = structureTreeRoot.getMappedClass(name);
            if (put != null) {
                if (!compareObjects(put, object)) {
                    throw new BadPdfFormatException(MessageLocalization.getComposedMessage("conflict.in.classmap", name));
                }
            } else {
                if (object.isDictionary())
                    structureTreeRoot.mapClass(name, getDirectDict((PdfDictionary) object));
                else if (object.isArray()) {
                    structureTreeRoot.mapClass(name, getDirectArray((PdfArray) object));
                }
            }
        }
    }

    protected void addRole(PdfName structType) throws BadPdfFormatException {
        if (structType == null) {
            return;
        }
        for (PdfName name : standardTypes) {
            if (name.equals(structType))
                return;
        }
        if (sourceRoleMap == null) {
            PdfObject object = getDirectObject(structTreeRoot.get(PdfName.ROLEMAP));
            if (object == null || !object.isDictionary()) {
                return;
            }
            sourceRoleMap = (PdfDictionary) object;
        }
        PdfObject object = sourceRoleMap.get(structType);
        if (object == null || !object.isName()) {
            return;
        }
        PdfObject currentRole;
        if (roleMap == null) {
            roleMap = new PdfDictionary();
            structureTreeRoot.put(PdfName.ROLEMAP, roleMap);
            roleMap.put(structType, object);
        } else if ((currentRole = roleMap.get(structType)) != null) {
            if (!currentRole.equals(object)) {
                throw new BadPdfFormatException(MessageLocalization.getComposedMessage("conflict.in.rolemap", object));
            }
        } else {
            roleMap.put(structType, object);
        }
    }

    protected void addKid(PdfDictionary parent, PdfObject kid) {
        PdfObject kidObj = parent.get(PdfName.K);
        PdfArray kids;
        if (kidObj instanceof PdfArray) {
            kids = (PdfArray) kidObj;
        } else {
            kids = new PdfArray();
            if (kidObj != null)
                kids.add(kidObj);
        }
        kids.add(kid);
        parent.put(PdfName.K, kids);
    }

//    private int getDocumentHash(final PdfReader reader) {
//        PdfDictionary trailer = reader.trailer;
//        int hash = trailer.size();
//        HashMap<String, String> info = reader.getInfo();
//        PdfArray id = trailer.getAsArray(PdfName.ID);
//        if (id != null) {
//            for (PdfObject idPart : id) {
//                if (idPart instanceof PdfString) {
//                    hash = hash ^ ((PdfString)idPart).toUnicodeString().hashCode();
//                }
//            }
//        }
//        for (String key : info.keySet()) {
//            String value = info.get(key);
//            if (value != null) {
//                hash = hash ^ key.hashCode() ^ value.hashCode();
//            }
//        }
//        return hash;
//    }

}
