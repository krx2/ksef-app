-- Rename misleading column: fa2_xml stores FA(3) format XML, not FA(2)
ALTER TABLE invoices RENAME COLUMN fa2_xml TO fa3_xml;
