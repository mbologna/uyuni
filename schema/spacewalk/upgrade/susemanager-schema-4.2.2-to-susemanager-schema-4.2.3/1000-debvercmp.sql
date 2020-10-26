DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_type t
        JOIN pg_class c ON c.oid = t.typrelid
        JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE t.typname = 'evr_t'
        AND a.attname = 'type'
        )
    THEN
        alter type evr_t add attribute type varchar(10);
    END IF;
END $$;

drop index rhn_pe_v_r_e_uq;
create unique index rhn_pe_v_r_e_uq
    on rhnpackageevr (version, release, epoch, ((evr).type))
 where epoch is not null;

drop index rhn_pe_v_r_uq;
create unique index rhn_pe_v_r_uq
    on rhnpackageevr (version, release, ((evr).type))
 where epoch is null;

create or replace function evr_t(e varchar, v varchar, r varchar, t varchar)
returns evr_t as $$
select row($1,$2,$3,$4)::evr_t
$$ language sql;


-- update evr_t comparison function to take type into account.
create or replace function evr_t_compare( a evr_t, b evr_t )
returns int as $$
begin
  if a.type = b.type then
      if a.type = 'rpm' then
        return rpm.vercmp(a.epoch, a.version, a.release, b.epoch, b.version, b.release);
      elsif a.type = 'deb' then
        return deb.debvercmp(a.epoch, a.version, a.release, b.epoch, b.version, b.release);
      else
        raise notice 'unknown evr type';
      end if;
  else
     raise notice 'cant compare incompatible evr types';
  end if;
end;
$$ language plpgsql immutable strict;


-- update insert_evr
create or replace function
insert_evr(e_in in varchar, v_in in varchar, r_in in varchar, t_in in varchar)
returns numeric
as
$$
declare
    evr_id  numeric;
begin
    evr_id := nextval('rhn_pkg_evr_seq');

    insert into rhnPackageEVR(id, epoch, version, release, evr)
        values (evr_id, e_in, v_in, r_in, evr_t(e_in, v_in, r_in, t_in))
        on conflict do nothing;

    select id
        into strict evr_id
        from rhnPackageEVR
        where ((epoch is null and e_in is null) or (epoch = e_in)) and
           version = v_in and release = r_in and (evr).type = t_in;

    return evr_id;
end;
$$ language plpgsql;


-- update lookup_evr
create or replace function
lookup_evr2(e_in in varchar, v_in in varchar, r_in in varchar, t_in in varchar)
returns numeric
as
$$
declare
    evr_id  numeric;
begin
    select id
      into evr_id
      from rhnPackageEVR
     where ((epoch is null and e_in is null) or (epoch = e_in)) and
           version = v_in and
           release = r_in and
           (evr).type = t_in;

    if not found then
        -- HACK: insert is isolated in own function in order to be able to declare this function immutable
        -- Postgres optimizes immutable functions calls but those are compatible with the contract of lookup_\*
        -- see https://www.postgresql.org/docs/9.6/xfunc-volatility.html
        return insert_evr(e_in, v_in, r_in, t_in);
    end if;

    return evr_id;
end;
$$ language plpgsql immutable;


create or replace function
lookup_transaction_package(
    o_in in varchar,
    n_in in varchar,
    e_in in varchar,
    v_in in varchar,
    r_in in varchar,
    a_in in varchar)
returns numeric
as
$$
declare
    o_id        numeric;
    n_id        numeric;
    e_id        numeric;
    p_arch_id   numeric;
    tp_id       numeric;
    type        varchar;
begin
    select id
      into o_id
      from rhnTransactionOperation
     where label = o_in;

    if not found then
        perform rhn_exception.raise_exception('invalid_transaction_operation');
    end if;

    n_id := lookup_package_name(n_in);
    p_arch_id := null;

    if a_in is not null then
        p_arch_id := lookup_package_arch(a_in);
        SELECT t.label into type from rhnpackagearch pa join rhnarchtype t
         on t.id = pa.arch_type_id where pa.id = p_arch_id;
    end if;

    e_id := lookup_evr2(e_in, v_in, r_in, type);

    select id
      into tp_id
      from rhnTransactionPackage
     where operation = o_id and
           name_id = n_id and
           evr_id = e_id and
           (package_arch_id = p_arch_id or (p_arch_id is null and package_arch_id is null));

    if not found then
        -- HACK: insert is isolated in own function in order to be able to declare this function immutable
        -- Postgres optimizes immutable functions calls but those are compatible with the contract of lookup_\*
        -- see https://www.postgresql.org/docs/9.6/xfunc-volatility.html
        return insert_transaction_package(o_id, n_id, e_id, p_arch_id);
    end if;
    return tp_id;
end;
$$ language plpgsql immutable;


ALTER TABLE rhnpackageevr DISABLE TRIGGER USER;

-- set all existing rhnpackageevr to rpm
update rhnpackageevr set evr.type = 'rpm';

--    TABLE "rhnpackage" CONSTRAINT "rhn_package_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from rhnpackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnpackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnpackage.id = sub.id;

--    TABLE "rhnactionpackage" CONSTRAINT "rhn_act_p_evr_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from rhnactionpackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnactionpackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnactionpackage.id = sub.id;


--    TABLE "rhnactionpackageremovalfailure" CONSTRAINT "rhn_apr_failure_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.action_id, p.server_id, t.label, pe.evr
         from rhnactionpackageremovalfailure p
         join rhnserver s on s.id = p.server_id
         join rhnserverarch pa on s.server_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnactionpackageremovalfailure
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnactionpackageremovalfailure.server_id = sub.server_id and
      rhnactionpackageremovalfailure.action_id = sub.action_id;


--    TABLE "rhnchannelnewestpackage" CONSTRAINT "rhn_cnp_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, p.evr_id
         from rhnpackage p
)
update rhnchannelnewestpackage
set evr_id = sub.evr_id
from sub
where rhnchannelnewestpackage.package_id = sub.id;


--    TABLE "rhnpackagenevra" CONSTRAINT "rhn_pkgnevra_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from rhnpackagenevra p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnpackagenevra
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnpackagenevra.id = sub.id;

--    TABLE "rhnserverpackage" CONSTRAINT "rhnserverpackage_evr_id_fkey" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, p.name_id, p.evr_id, p.package_arch_id, t.label, pe.evr
         from rhnserverpackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnserverpackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnserverpackage.server_id = sub.server_id and
      rhnserverpackage.name_id = sub.name_id and
      rhnserverpackage.evr_id = sub.evr_id and
      rhnserverpackage.package_arch_id = sub.package_arch_id;

--    TABLE "suseproductfile" CONSTRAINT "suse_prod_file_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from suseproductfile p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update suseproductfile
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where suseproductfile.id = sub.id;

--    TABLE "suseimageinfopackage" CONSTRAINT "suseimageinfopackage_evr_id_fkey" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.image_info_id, p.evr_id, p.name_id, p.package_arch_id, t.label, pe.evr
         from suseimageinfopackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update suseimageinfopackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where suseimageinfopackage.image_info_id = sub.image_info_id and
      suseimageinfopackage.evr_id = sub.evr_id and
      suseimageinfopackage.name_id = sub.name_id and
      suseimageinfopackage.package_arch_id = sub.package_arch_id;


--    TABLE "rhnversioninfo" CONSTRAINT "rhn_versioninfo_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.label, pe.evr
         from rhnversioninfo p
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnversioninfo
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, 'rpm')
from sub
where rhnversioninfo.label = sub.label;


--    TABLE "rhnproxyinfo" CONSTRAINT "rhn_proxy_info_peid_fk" FOREIGN KEY (proxy_evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, t.label, pe.evr
         from rhnproxyinfo p
         join rhnserver s on s.id = p.server_id
         join rhnserverarch pa on s.server_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.proxy_evr_id = pe.id
)
update rhnproxyinfo
set proxy_evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnproxyinfo.server_id = sub.server_id;


--    TABLE "rhnsatelliteinfo" CONSTRAINT "rhn_satellite_info_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, t.label, pe.evr
         from rhnsatelliteinfo p
         join rhnserver s on s.id = p.server_id
         join rhnserverarch pa on s.server_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnsatelliteinfo
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnsatelliteinfo.server_id = sub.server_id;


--    TABLE "rhnserverprofilepackage" CONSTRAINT "rhn_sprofile_evrid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_profile_id, p.name_id, p.evr_id, t.label, pe.evr
         from rhnserverprofilepackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnserverprofilepackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnserverprofilepackage.server_profile_id = sub.server_profile_id and
      rhnserverprofilepackage.name_id = sub.name_id and
      rhnserverprofilepackage.evr_id = sub.evr_id;


--    TABLE "rhnservercrash" CONSTRAINT "rhn_server_crash_evr_id_fk" FOREIGN KEY (package_evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, p.crash, t.label, pe.evr
         from rhnservercrash p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.package_evr_id = pe.id
)
update rhnservercrash
set package_evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnservercrash.server_id = sub.server_id and
      rhnservercrash.crash = sub.crash;


--    TABLE "rhnlockedpackages" CONSTRAINT "rhnlockedpackages_evr_id_fkey" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, p.name_id, p.evr_id, p.arch_id, t.label, pe.evr
         from rhnlockedpackages p
         join rhnpackagearch pa on p.arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhnlockedpackages
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnlockedpackages.server_id = sub.server_id and
      rhnlockedpackages.name_id = sub.name_id and
      rhnlockedpackages.evr_id = sub.evr_id and
      rhnlockedpackages.arch_id = sub.arch_id;


--    TABLE "susepackagestate" CONSTRAINT "suse_pkg_state_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from susepackagestate p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update susepackagestate
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where susepackagestate.id = sub.id;


--    TABLE "rhnserveractionverifymissing" CONSTRAINT "rhn_sactionvm_peid_fk" FOREIGN KEY (package_evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, p.action_id, p.package_name_id, p.package_evr_id, p.package_arch_id, p.package_capability_id, t.label, pe.evr
         from rhnserveractionverifymissing p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.package_evr_id = pe.id
)
update rhnserveractionverifymissing
set package_evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnserveractionverifymissing.server_id = sub.server_id and
      rhnserveractionverifymissing.action_id = sub.action_id and
      rhnserveractionverifymissing.package_name_id = sub.package_name_id and
      rhnserveractionverifymissing.package_evr_id = sub.package_evr_id and
      rhnserveractionverifymissing.package_arch_id = sub.package_arch_id and
      rhnserveractionverifymissing.package_capability_id = sub.package_capability_id;


--    TABLE "rhnserveractionverifyresult" CONSTRAINT "rhn_sactionvr_peid_fk" FOREIGN KEY (package_evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.server_id, p.action_id, p.package_name_id, p.package_evr_id, p.package_arch_id, p.package_capability_id, t.label, pe.evr
         from rhnserveractionverifyresult p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.package_evr_id = pe.id
)
update rhnserveractionverifyresult
set package_evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhnserveractionverifyresult.server_id = sub.server_id and
      rhnserveractionverifyresult.action_id = sub.action_id and
      rhnserveractionverifyresult.package_name_id = sub.package_name_id and
      rhnserveractionverifyresult.package_evr_id = sub.package_evr_id and
      rhnserveractionverifyresult.package_arch_id = sub.package_arch_id and
      rhnserveractionverifyresult.package_capability_id = sub.package_capability_id;


--    TABLE "rhntransactionpackage" CONSTRAINT "rhn_transpack_eid_fk" FOREIGN KEY (evr_id) REFERENCES rhnpackageevr(id)
with sub as (
    select p.id, t.label, pe.evr
         from rhntransactionpackage p
         join rhnpackagearch pa on p.package_arch_id = pa.id
         join rhnarchtype t on pa.arch_type_id = t.id
         join rhnpackageevr pe on p.evr_id = pe.id
)
update rhntransactionpackage
set evr_id = lookup_evr2((sub.evr).epoch, (sub.evr).version, (sub.evr).release, sub.label)
from sub
where rhntransactionpackage.id = sub.id;

-- delete all rhnpackageevr entries that are not referenced anymore
delete from rhnpackageevr p
 using (
    select p.id, p.evr from rhnpackageevr p left join
        rhnactionpackage f1 on f1.evr_id = p.id left join
        rhnactionpackageremovalfailure f2 on f2.evr_id = p.id left join
        rhnchannelnewestpackage f3 on f3.evr_id = p.id left join
        rhnpackage f4 on f4.evr_id = p.id left join
        rhnpackagenevra f5 on f5.evr_id = p.id left join
        rhnproxyinfo f6 on f6.proxy_evr_id = p.id left join
        rhnserveractionverifymissing f7 on f7.package_evr_id = p.id left join
        rhnserveractionverifyresult f8 on f8.package_evr_id = p.id left join
        rhnsatelliteinfo f9 on f9.evr_id = p.id left join
        rhnservercrash f10 on f10.package_evr_id = p.id left join
        rhnserverprofilepackage f11 on f11.evr_id = p.id left join
        rhntransactionpackage f12 on f12.evr_id = p.id left join
        rhnversioninfo f13 on f13.evr_id = p.id left join
        rhnlockedpackages f14 on f14.evr_id = p.id left join
        rhnserverpackage f15 on f15.evr_id = p.id left join
        susepackagestate f16 on f16.evr_id = p.id left join
        suseproductfile f17 on f17.evr_id = p.id left join
        suseimageinfopackage f18 on f18.evr_id = p.id
    where f1.evr_id is null and
          f2.evr_id is null and
          f3.evr_id is null and
          f4.evr_id is null and
          f5.evr_id is null and
          f6.proxy_evr_id is null and
          f7.package_evr_id is null and
          f8.package_evr_id is null and
          f9.evr_id is null and
          f10.package_evr_id is null and
          f11.evr_id is null and
          f12.evr_id is null and
          f13.evr_id is null and
          f14.evr_id is null and
          f15.evr_id is null and
          f16.evr_id is null and
          f17.evr_id is null and
          f18.evr_id is null
 ) as sub
where p.id = sub.id;


-- TODO one potential option
alter table rhnpackageevr add column if not exists type varchar(10) generated always as ((evr).type) stored;

ALTER TABLE rhnpackageevr ENABLE TRIGGER USER;